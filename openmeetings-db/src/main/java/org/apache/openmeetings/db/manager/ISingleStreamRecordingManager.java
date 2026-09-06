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
	 * @return a caller-supplied-free, server-generated request id identifying
	 *         this recording, needed to call {@link #stopSingle(Long, String)}
	 * @throws IllegalStateException with a descriptive, caller-facing message
	 *                               for every refusal case (media server not
	 *                               connected, no/ambiguous matching
	 *                               participant, no/ambiguous active webcam
	 *                               stream, no video) -- never guesses on
	 *                               ambiguity
	 */
	String startSingle(Long roomId, String externalUserId);

	/**
	 * Idempotent: if the recording already auto-stopped (e.g. the participant
	 * disconnected, which independently triggers the same stop path), this
	 * returns quietly rather than erroring -- a caller racing the natural
	 * end of a session should never see a failure just for being slightly
	 * late.
	 *
	 * @param roomId room the recording was started in
	 * @param requestId the id returned by {@link #startSingle(Long, String)}
	 */
	void stopSingle(Long roomId, String requestId);
}
