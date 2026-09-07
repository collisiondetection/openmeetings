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

import org.apache.openmeetings.db.dto.record.SingleStreamRecordingStart;

/**
 * Records ONE participant's own stream in a room, independent of the room's
 * normal Record button -- interface lives here (openmeetings-db) so
 * openmeetings-webservice can depend on it despite the module graph
 * forbidding a direct dependency on openmeetings-mediaserver, where the
 * real implementation lives (same pattern as {@link IClientManager} and
 * {@link IWhiteboardManager}).
 */
public interface ISingleStreamRecordingManager {
	/**
	 * @param roomId room to search for the target participant in
	 * @param externalUserId the Moodle user id to target, matched against
	 *                       {@code Client.getUser().getExternalId()}
	 * @return a server-generated request id identifying this recording
	 *         (needed to call {@link #stopSingle(Long, String)}), together
	 *         with the OM server's own wall-clock instant the recording was
	 *         activated -- see {@link SingleStreamRecordingStart}
	 * @throws IllegalStateException with a descriptive, caller-facing message
	 *                               for every refusal case (media server not
	 *                               connected, no/ambiguous matching
	 *                               participant, no/ambiguous active webcam
	 *                               stream, no video) -- never guesses on
	 *                               ambiguity
	 */
	SingleStreamRecordingStart startSingle(Long roomId, String externalUserId);

	/**
	 * Idempotent: if the recording already auto-stopped (e.g. the participant
	 * disconnected, which independently triggers the same stop path), this
	 * returns quietly rather than erroring -- a caller racing the natural
	 * end of a session should never see a failure just for being slightly
	 * late.
	 *
	 * <p>Blocks the calling thread until the media server has genuinely
	 * confirmed the stop (or a bounded timeout elapses) before returning --
	 * this is a real wait, not a formality: the implementation's own
	 * completion signal is asynchronous by nature (see
	 * {@code KStream.stopSingleRecord}'s javadoc), so a caller that needs to
	 * know whether the recorded file is safe to hand off for conversion
	 * cannot get that answer any earlier than this.
	 *
	 * @param roomId room the recording was started in
	 * @param requestId the id returned by {@link #startSingle(Long, String)}
	 * @return {@code true} if the recording is confirmed stopped and the
	 *         resulting chunk is safe to convert -- also {@code true} for
	 *         the idempotent "nothing to stop" case above; {@code false} if
	 *         the media server reported a failure stopping it, or did not
	 *         confirm within the implementation's own timeout, in which
	 *         case the caller should NOT attempt to convert the chunk
	 */
	boolean stopSingle(Long roomId, String requestId);
}
