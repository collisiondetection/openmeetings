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
package org.apache.openmeetings.db.dto.room;

/**
 * The result of successfully adding a browser-less RTP participant to a room
 * via {@link org.apache.openmeetings.db.manager.IRtpParticipantManager#join}.
 *
 * Purely an in-JVM handoff between that manager (openmeetings-mediaserver) and
 * its webservice caller (openmeetings-webservice) -- deliberately NOT the wire
 * type. The webservice layer marshals this into its own JSON DTO, exactly the
 * way {@link org.apache.openmeetings.db.dto.record.SingleStreamRecordingStart}
 * is mapped into a {@code ServiceResult}/{@code SingleStreamRecordingStartResult}
 * one layer up. No JAXB annotations, for the same reason -- it is never
 * marshalled directly, so it keeps the REST-response-shaped concepts out of the
 * manager/media-server layer.
 */
public class RtpParticipantJoinResult {
	private final String sessionId;
	private final String sdpAnswer;
	private final String reverseSdpAnswer;
	private final String clientUid;
	private final String streamUid;

	/**
	 * @param sessionId server-generated id identifying this RTP-participant
	 *                  session -- the caller passes it back to
	 *                  {@link org.apache.openmeetings.db.manager.IRtpParticipantManager#end(String)}
	 *                  on normal teardown
	 * @param sdpAnswer Kurento's SDP answer for the FORWARD leg (bot -> room):
	 *                  its own receive IP+ports, which the caller's external
	 *                  RTP source (ffmpeg) must be pointed at
	 * @param reverseSdpAnswer Kurento's SDP answer for the REVERSE leg
	 *                         (room -> bot), or {@code null} if no reverse leg
	 *                         was requested, or if it was requested but is
	 *                         deferred because no real participant was in the
	 *                         room yet to graft it onto (it will be wired when
	 *                         one joins -- see
	 *                         {@code KRoom.activateAiStandinReverseLeg})
	 * @param clientUid the synthetic participant's own client uid (diagnostics)
	 * @param streamUid the synthetic participant's own stream uid (diagnostics)
	 */
	public RtpParticipantJoinResult(String sessionId, String sdpAnswer, String reverseSdpAnswer, String clientUid, String streamUid) {
		this.sessionId = sessionId;
		this.sdpAnswer = sdpAnswer;
		this.reverseSdpAnswer = reverseSdpAnswer;
		this.clientUid = clientUid;
		this.streamUid = streamUid;
	}

	public String getSessionId() {
		return sessionId;
	}

	public String getSdpAnswer() {
		return sdpAnswer;
	}

	public String getReverseSdpAnswer() {
		return reverseSdpAnswer;
	}

	public String getClientUid() {
		return clientUid;
	}

	public String getStreamUid() {
		return streamUid;
	}
}
