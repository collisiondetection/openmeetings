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
package org.apache.openmeetings.db.manager;

import org.apache.openmeetings.db.dto.room.RtpParticipantJoinResult;

/**
 * Adds a browser-less participant to a room whose media enters and leaves via
 * plain Kurento {@code RtpEndpoint}s fed by an external RTP source (e.g. an
 * ffmpeg process bridging a third-party media provider), instead of a real
 * browser or a SIP/Asterisk leg.
 *
 * <p>Built for the AI teacher stand-in: a headless media bridge sends the
 * stand-in's audio (optionally video) into a class over RTP, and receives the
 * real participant's audio back over a second, dedicated RTP leg, with no
 * browser re-capture hop in between.
 *
 * <p>Interface lives here (openmeetings-db) so openmeetings-webservice can
 * depend on it despite the module graph forbidding a direct dependency on
 * openmeetings-mediaserver, where the real implementation lives -- the same
 * split as {@link ISingleStreamRecordingManager}/{@link IClientManager}/
 * {@link IWhiteboardManager}.
 */
public interface IRtpParticipantManager {
	/**
	 * Add a browser-less RTP participant, identified by a real, already-provisioned
	 * OpenMeetings external user, to {@code roomId}.
	 *
	 * <p><b>Identity is real and fail-closed.</b> {@code externalId}/
	 * {@code externalType} are resolved to a genuine persisted OM user via
	 * {@code UserDao.getExternalUser} -- the exact record every human
	 * participant is matched against -- so every {@code conference_log} row this
	 * session produces (ROOM_ENTER on join, ROOM_LEAVE/CLIENT_DISCONNECT on
	 * teardown) is correctly person-scoped to that user id, which the Moodle
	 * plugin's attendance/pay logic depends on. If no such user exists this
	 * throws {@link IllegalStateException} rather than inventing one -- the
	 * caller (Moodle) must provision the bot's OM user at least once before the
	 * first join, exactly as it already lazily provisions every other OM user.
	 *
	 * @param roomId room to add the participant to
	 * @param externalId the participant's external (Moodle) user id
	 * @param externalType the participant's external type (the plugin's OM
	 *                     "moodle key"/module identifier), matched together with
	 *                     {@code externalId}
	 * @param videoEnabled whether to advertise/carry a video track as well as
	 *                     audio (audio-only when false -- no video m-line is
	 *                     expected in the offer)
	 * @param width advertised tile width (video only; ignored for audio-only)
	 * @param height advertised tile height (video only; ignored for audio-only)
	 * @param sdpOffer the FORWARD-leg SDP offer (sendonly, from the external
	 *                 RTP source) describing what the bridge will send; Kurento
	 *                 answers with its own receive IP+ports
	 * @param reverseSdpOffer the REVERSE-leg SDP offer (recvonly, describing the
	 *                        bridge's own listening IP+port), or {@code null}/blank
	 *                        to skip the reverse leg entirely; when present, the
	 *                        real participant's audio is grafted onto a separate,
	 *                        dedicated {@code RtpEndpoint} pointed at that address
	 * @param maxDurationSeconds the caller's own hold ceiling (+margin) after
	 *                           which the watchdog force-ends this session as a
	 *                           runaway backstop; {@code 0} to use the server's
	 *                           own default. The caller (which knows its real hold
	 *                           ceiling) passing this is what makes the backstop
	 *                           "just above" that ceiling in practice.
	 * @return {@link RtpParticipantJoinResult} carrying the session id (for
	 *         {@link #end(String)}), Kurento's forward SDP answer, and the
	 *         reverse SDP answer (or null if no reverse leg / deferred)
	 * @throws IllegalStateException with a descriptive, caller-facing message
	 *                               for every refusal case (media server not
	 *                               connected, no such room, no such external
	 *                               user, endpoint setup failure)
	 */
	RtpParticipantJoinResult join(Long roomId, String externalId, String externalType, boolean videoEnabled, int width, int height, String sdpOffer, String reverseSdpOffer, long maxDurationSeconds);

	/**
	 * End a session started by {@link #join}, releasing its Kurento media and
	 * removing it from the room roster (which emits the ROOM_LEAVE/
	 * CLIENT_DISCONNECT {@code conference_log} rows).
	 *
	 * <p><b>Idempotent.</b> Calling this for a session that has already ended --
	 * by an explicit earlier {@code end}, or reaped by the watchdog because its
	 * media died or it outlived its backstop -- returns {@code false} quietly,
	 * never throwing and never double-logging a second ROOM_LEAVE. A caller's
	 * normal teardown racing the watchdog is therefore always safe.
	 *
	 * @param sessionId the id returned by {@link #join}
	 * @return {@code true} if this call was the one that actually ended a live
	 *         session; {@code false} if there was nothing (still) to end
	 */
	boolean end(String sessionId);
}
