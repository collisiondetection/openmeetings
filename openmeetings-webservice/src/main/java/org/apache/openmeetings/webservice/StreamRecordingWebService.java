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
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import org.apache.cxf.feature.Features;
import org.apache.openmeetings.db.dto.basic.ServiceResult;
import org.apache.openmeetings.db.dto.basic.ServiceResult.Type;
import org.apache.openmeetings.db.entity.user.User;
import org.apache.openmeetings.db.manager.ISingleStreamRecordingManager;
import org.apache.openmeetings.webservice.error.ServiceException;
import org.apache.openmeetings.webservice.schema.ServiceResultWrapper;
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
 * Records ONE participant's own stream, independent of the room's normal
 * Record button -- see {@link ISingleStreamRecordingManager}. Not part of
 * OM's normal recording-registration pipeline: the caller (a Moodle plugin
 * batch job, not OM) is responsible for locating and converting the raw
 * chunk this produces.
 */
@Service("streamRecordingWebService")
@WebService(serviceName="org.apache.openmeetings.webservice.StreamRecordingWebService", targetNamespace = TNS)
@Features(features = "org.apache.cxf.ext.logging.LoggingFeature")
@Produces({MediaType.APPLICATION_JSON})
@Tag(name = "StreamRecordingService")
@Path("/streamrecording")
public class StreamRecordingWebService extends BaseWebService {
	private static final Logger log = LoggerFactory.getLogger(StreamRecordingWebService.class);

	@Inject
	private ISingleStreamRecordingManager recManager;

	/**
	 * @param sid - The SID of the User. This SID must be marked as Loggedin
	 * @param roomId - id of the room the target participant is currently in
	 * @param externalUserId - the Moodle user id to target (matched against
	 *                       the participant's own externalId)
	 * @return - serviceResult object; on success, message holds the request id
	 *         needed to call stopSingle later
	 * @throws {@link ServiceException} in case of any errors
	 */
	@WebMethod
	@GET
	@Path("/start/{roomid}/{externaluserid}")
	@Operation(
			description = "Starts recording ONE participant's own stream, independent of the room's normal Record button",
			responses = {
					@ApiResponse(responseCode = "200", description = "serviceResult object with the result",
							content = @Content(schema = @Schema(implementation = ServiceResultWrapper.class))),
					@ApiResponse(responseCode = "500", description = "Error in case of invalid credentials or server error")
			}
		)
	public ServiceResult startSingle(
			@Parameter(required = true, description = "The SID of the User. This SID must be marked as Loggedin") @WebParam(name="sid") @QueryParam("sid") String sid
			, @Parameter(required = true, description = "id of the room the target participant is currently in") @WebParam(name="roomid") @PathParam("roomid") long roomId
			, @Parameter(required = true, description = "the Moodle user id to target") @WebParam(name="externaluserid") @PathParam("externaluserid") String externalUserId
			) throws ServiceException
	{
		log.debug("[startSingle] room id {}, externalUserId {}", roomId, externalUserId);
		return performCall(sid, User.Right.SOAP, sd -> {
			try {
				String requestId = recManager.startSingle(roomId, externalUserId);
				return new ServiceResult(requestId, Type.SUCCESS);
			} catch (IllegalStateException e) {
				// Every refusal from the manager is a descriptive message meant
				// for the caller (e.g. a Moodle scheduled task deciding whether
				// to retry) -- returned as a normal ServiceResult, not left to
				// propagate into performCall's own generic catch, which would
				// wrap it as an opaque thrown ServiceException instead.
				log.info("[startSingle] refused: {}", e.getMessage());
				return new ServiceResult(e.getMessage(), Type.ERROR);
			}
		});
	}

	/**
	 * Idempotent -- stopping an already-stopped (e.g. the participant
	 * disconnected) recording is not an error.
	 *
	 * @param sid - The SID of the User. This SID must be marked as Loggedin
	 * @param roomId - id of the room the recording was started in
	 * @param requestId - the id returned by startSingle
	 * @return - serviceResult object with the result
	 * @throws {@link ServiceException} in case of any errors
	 */
	@WebMethod
	@GET
	@Path("/stop/{roomid}/{requestid}")
	@Operation(
			description = "Stops a single-participant recording started by startSingle",
			responses = {
					@ApiResponse(responseCode = "200", description = "serviceResult object with the result",
							content = @Content(schema = @Schema(implementation = ServiceResultWrapper.class))),
					@ApiResponse(responseCode = "500", description = "Error in case of invalid credentials or server error")
			}
		)
	public ServiceResult stopSingle(
			@Parameter(required = true, description = "The SID of the User. This SID must be marked as Loggedin") @WebParam(name="sid") @QueryParam("sid") String sid
			, @Parameter(required = true, description = "id of the room the recording was started in") @WebParam(name="roomid") @PathParam("roomid") long roomId
			, @Parameter(required = true, description = "the id returned by startSingle") @WebParam(name="requestid") @PathParam("requestid") String requestId
			) throws ServiceException
	{
		log.debug("[stopSingle] room id {}, requestId {}", roomId, requestId);
		return performCall(sid, User.Right.SOAP, sd -> {
			recManager.stopSingle(roomId, requestId);
			return new ServiceResult("", Type.SUCCESS);
		});
	}
}
