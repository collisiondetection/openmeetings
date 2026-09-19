/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License") +  you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.openmeetings.webservice;

import static org.apache.openmeetings.webservice.Constants.TNS;

import jakarta.inject.Inject;
import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebService;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.apache.cxf.feature.Features;
import org.apache.openmeetings.db.dto.basic.ServiceResult;
import org.apache.openmeetings.db.dto.room.ClusterTopologyResponse;
import org.apache.openmeetings.db.entity.user.User;
import org.apache.openmeetings.db.manager.IClientManager;
import org.apache.openmeetings.webservice.error.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Read-only cluster room-topology lookup -- which server currently hosts each
 * room, cluster-wide, answerable from ANY node. The underlying data
 * ({@link IClientManager#serverRoomsByUrl()}) is Hazelcast-replicated -- the
 * same per-server state the redirect balancer ({@code
 * ClientManager.getServerUrl()}) already reads -- so querying one node's own
 * address returns the whole cluster's view, not merely what that node itself
 * hosts.
 *
 * <p>Closes a real, existing gap: {@code ais4_join.php}, {@code
 * wb_recording_manage.php}, and {@code participant_recording_manage.php} each
 * currently guess which configured node to call by trying every one in turn
 * until it accepts, because nothing previously told the caller which node
 * actually hosts a given room. One call here replaces that guesswork. It also
 * answers "does node X currently host anything" -- researched (not yet built)
 * as the idle-before-terminate check an elastic OM node fleet would need; see
 * CLAUDE.md's OM node-elasticity research section, 2026-09-18.
 *
 * <p>Auth reuses OM's existing SOAP session, matching {@link
 * RtpParticipantWebService}/{@link StreamRecordingWebService} -- {@code
 * performCall(sid, User.Right.SOAP, ...)}. The caller (the Moodle plugin)
 * already holds a live {@code sid} from its normal OM login for every other
 * OM call it makes, so no new shared secret is introduced.
 */
@Service("clusterInfoWebService")
@WebService(serviceName="org.apache.openmeetings.webservice.ClusterInfoWebService", targetNamespace = TNS)
@Features(features = "org.apache.cxf.ext.logging.LoggingFeature")
@Produces({MediaType.APPLICATION_JSON})
@Tag(name = "ClusterInfoService")
@Path("/clusterinfo")
public class ClusterInfoWebService extends BaseWebService {
	private static final Logger log = LoggerFactory.getLogger(ClusterInfoWebService.class);

	@Inject
	private IClientManager clientManager;

	/**
	 * @param sid a live OM SOAP session id (marked Loggedin)
	 * @return every currently-known server's base URL mapped to the room ids
	 *         it presently hosts (an empty list means that server is idle)
	 * @throws ServiceException on invalid credentials
	 */
	@WebMethod
	@POST
	@Path("/topology")
	@Operation(
			description = "Lists every cluster server's currently-hosted room ids",
			responses = {
					@ApiResponse(responseCode = "200", description = "serviceResult with the per-server room-hosting map",
							content = @Content(schema = @Schema(implementation = ClusterTopologyResponse.class))),
					@ApiResponse(responseCode = "500", description = "Error in case of invalid credentials or server error")
			}
		)
	public ClusterTopologyResponse topology(
			@Parameter(required = true, description = "The SID of the User. This SID must be marked as Loggedin") @WebParam(name="sid") @FormParam("sid") String sid
			) throws ServiceException
	{
		return performCall(sid, User.Right.SOAP, sd -> {
			ClusterTopologyResponse resp = new ClusterTopologyResponse(
					clientManager.serverRoomsByUrl(), clientManager.serverDrainingByUrl(), clientManager.selfUrl());
			log.debug("[clusterinfo topology] {} server(s)", resp.getServers().size());
			return resp;
		});
	}

	/**
	 * Marks/unmarks THIS node (whichever server actually receives the call --
	 * see {@link IClientManager#setDraining(boolean)}) as draining: excluded
	 * from the redirect balancer's pick for a brand-new room, but with rooms
	 * it already hosts completely unaffected. A manual operator lever today;
	 * the piece an automated scale-down would call before terminating a node,
	 * once that automation exists.
	 *
	 * @param sid a live OM SOAP session id (marked Loggedin)
	 * @param draining true to start draining this node, false to accept new
	 *                 rooms again
	 * @return a SUCCESS {@link ServiceResult}-shaped response confirming the
	 *         new state
	 * @throws ServiceException on invalid credentials
	 */
	@WebMethod
	@POST
	@Path("/drain")
	@Operation(
			description = "Marks or unmarks THIS OpenMeetings node as draining (excluded from new-room assignment)",
			responses = {
					@ApiResponse(responseCode = "200", description = "serviceResult confirming the new draining state"),
					@ApiResponse(responseCode = "500", description = "Error in case of invalid credentials or server error")
			}
		)
	public ServiceResult drain(
			@Parameter(required = true, description = "The SID of the User. This SID must be marked as Loggedin") @WebParam(name="sid") @FormParam("sid") String sid
			, @Parameter(required = true, description = "true to drain this node, false to accept new rooms again") @WebParam(name="draining") @FormParam("draining") boolean draining
			) throws ServiceException
	{
		return performCall(sid, User.Right.SOAP, sd -> {
			clientManager.setDraining(draining);
			log.info("[clusterinfo drain] this node draining={}", draining);
			return new ServiceResult(draining ? "draining" : "not draining", ServiceResult.Type.SUCCESS);
		});
	}
}
