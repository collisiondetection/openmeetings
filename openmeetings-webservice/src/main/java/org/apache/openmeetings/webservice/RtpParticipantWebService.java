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
import org.apache.openmeetings.db.dto.basic.ServiceResult.Type;
import org.apache.openmeetings.db.dto.room.RtpParticipantJoinResponse;
import org.apache.openmeetings.db.dto.room.RtpParticipantJoinResult;
import org.apache.openmeetings.db.entity.user.User;
import org.apache.openmeetings.db.manager.IRtpParticipantManager;
import org.apache.openmeetings.webservice.error.ServiceException;
import org.apache.openmeetings.webservice.schema.RtpParticipantJoinResponseWrapper;
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
 * Adds and removes a browser-less RTP participant -- the AI teacher stand-in --
 * whose media enters/leaves a room over plain Kurento {@code RtpEndpoint}s fed
 * by an external media bridge, with no browser re-capture hop. See
 * {@link IRtpParticipantManager}.
 *
 * <p><b>Auth reuses OM's existing SOAP session</b> -- {@code performCall(sid,
 * User.Right.SOAP, ...)}, exactly as {@link StreamRecordingWebService} does.
 * The caller (the Moodle plugin) already holds a live {@code sid} from its
 * normal OM login for every other OM call it makes, so no new shared secret is
 * introduced or distributed.
 *
 * <p>Both operations are {@code @POST} with {@code @FormParam}s: the SDP offers
 * are multi-line text blobs, not query-string-safe, and keeping {@code sid} in
 * the form body (not the query string) keeps it out of access logs.
 */
@Service("rtpParticipantWebService")
@WebService(serviceName="org.apache.openmeetings.webservice.RtpParticipantWebService", targetNamespace = TNS)
@Features(features = "org.apache.cxf.ext.logging.LoggingFeature")
@Produces({MediaType.APPLICATION_JSON})
@Tag(name = "RtpParticipantService")
@Path("/rtpparticipant")
public class RtpParticipantWebService extends BaseWebService {
	private static final Logger log = LoggerFactory.getLogger(RtpParticipantWebService.class);

	@Inject
	private IRtpParticipantManager rtpManager;

	/**
	 * Add a browser-less RTP participant to a room.
	 *
	 * @param sid a live OM SOAP session id (marked Loggedin)
	 * @param roomId the room to add the participant to
	 * @param externalId the participant's external (Moodle) user id -- resolved
	 *                   to a REAL, already-provisioned OM user (fail-closed)
	 * @param externalType the participant's external type (the plugin's OM
	 *                     "moodle key")
	 * @param videoEnabled whether to carry a video track as well as audio
	 * @param width advertised tile width (video only)
	 * @param height advertised tile height (video only)
	 * @param sdpOffer the FORWARD-leg (bridge -> room) sendonly SDP offer
	 * @param reverseSdpOffer the REVERSE-leg (room -> bridge) recvonly SDP offer,
	 *                        or blank to skip the reverse leg
	 * @param maxDurationSeconds caller's own hold ceiling (+margin) for the
	 *                           watchdog backstop; 0 to use the server default
	 * @return a {@link RtpParticipantJoinResponse} carrying the session id and
	 *         Kurento's SDP answers on success, or an ERROR result
	 * @throws ServiceException on invalid credentials
	 */
	@WebMethod
	@POST
	@Path("/join")
	@Operation(
			description = "Adds a browser-less RTP participant (AI stand-in) to a room",
			responses = {
					@ApiResponse(responseCode = "200", description = "on success, sessionId + Kurento SDP answers; else an ERROR serviceResult",
							content = @Content(schema = @Schema(implementation = RtpParticipantJoinResponseWrapper.class))),
					@ApiResponse(responseCode = "500", description = "Error in case of invalid credentials or server error")
			}
		)
	public RtpParticipantJoinResponse join(
			@Parameter(required = true, description = "The SID of the User. This SID must be marked as Loggedin") @WebParam(name="sid") @FormParam("sid") String sid
			, @Parameter(required = true, description = "the room to add the participant to") @WebParam(name="roomid") @FormParam("roomid") Long roomId
			, @Parameter(required = true, description = "the external (Moodle) user id of the stand-in") @WebParam(name="externalid") @FormParam("externalid") String externalId
			, @Parameter(required = true, description = "the external type (plugin OM moodle key)") @WebParam(name="externaltype") @FormParam("externaltype") String externalType
			, @Parameter(description = "carry a video track as well as audio") @WebParam(name="videoenabled") @FormParam("videoenabled") boolean videoEnabled
			, @Parameter(description = "advertised tile width (video only)") @WebParam(name="width") @FormParam("width") int width
			, @Parameter(description = "advertised tile height (video only)") @WebParam(name="height") @FormParam("height") int height
			, @Parameter(required = true, description = "forward-leg (bridge -> room) sendonly SDP offer") @WebParam(name="sdpoffer") @FormParam("sdpoffer") String sdpOffer
			, @Parameter(description = "reverse-leg (room -> bridge) recvonly SDP offer, or blank to skip") @WebParam(name="reversesdpoffer") @FormParam("reversesdpoffer") String reverseSdpOffer
			, @Parameter(description = "caller hold ceiling +margin for the watchdog, seconds; 0 for server default") @WebParam(name="maxdurationseconds") @FormParam("maxdurationseconds") long maxDurationSeconds
			) throws ServiceException
	{
		log.debug("[rtpparticipant join] room {}, externalId {}, video {}", roomId, externalId, videoEnabled);
		return performCall(sid, User.Right.SOAP, sd -> {
			try {
				RtpParticipantJoinResult r = rtpManager.join(roomId, externalId, externalType, videoEnabled, width, height, sdpOffer, reverseSdpOffer, maxDurationSeconds);
				return new RtpParticipantJoinResponse(r);
			} catch (IllegalStateException e) {
				// Every refusal from the manager is a descriptive, caller-facing
				// message -- returned as an ERROR result, not left to propagate
				// into performCall's generic catch (which would wrap it as an
				// opaque HTTP 500). Matches StreamRecordingWebService.startSingle.
				log.info("[rtpparticipant join] refused: {}", e.getMessage());
				return new RtpParticipantJoinResponse(e.getMessage(), Type.ERROR);
			}
		});
	}

	/**
	 * End a session started by {@link #join}. Idempotent -- ending an
	 * already-ended session (explicit or watchdog-reaped) is a SUCCESS, not an
	 * error, so a caller racing the watchdog on normal teardown never fails.
	 *
	 * @param sid a live OM SOAP session id (marked Loggedin)
	 * @param sessionId the id returned by {@link #join}
	 * @return a SUCCESS {@link ServiceResult}
	 * @throws ServiceException on invalid credentials
	 */
	@WebMethod
	@POST
	@Path("/end")
	@Operation(
			description = "Ends an RTP-participant session started by join (idempotent)",
			responses = {
					@ApiResponse(responseCode = "200", description = "serviceResult object with the result",
							content = @Content(schema = @Schema(implementation = ServiceResultWrapper.class))),
					@ApiResponse(responseCode = "500", description = "Error in case of invalid credentials or server error")
			}
		)
	public ServiceResult end(
			@Parameter(required = true, description = "The SID of the User. This SID must be marked as Loggedin") @WebParam(name="sid") @FormParam("sid") String sid
			, @Parameter(required = true, description = "the session id returned by join") @WebParam(name="sessionid") @FormParam("sessionid") String sessionId
			) throws ServiceException
	{
		log.debug("[rtpparticipant end] sessionId {}", sessionId);
		return performCall(sid, User.Right.SOAP, sd -> {
			boolean ended = rtpManager.end(sessionId);
			// Idempotent by contract: false means "nothing (still) to end",
			// which is a successful no-op from the caller's point of view, not
			// an error.
			return new ServiceResult(ended ? "ended" : "already ended", Type.SUCCESS);
		});
	}
}
