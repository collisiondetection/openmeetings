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
package org.apache.openmeetings.db.dto.record;

/**
 * The result of successfully starting a single-participant recording via
 * {@link org.apache.openmeetings.db.manager.ISingleStreamRecordingManager#startSingle(Long, String)}.
 *
 * Purely an in-JVM handoff between that manager and its webservice caller --
 * {@code ServiceResult} (the actual wire type) lives one layer up, in the
 * webservice module's own dependency, and is deliberately never referenced
 * from here or from openmeetings-mediaserver: today {@code ServiceResult} is
 * used exclusively by openmeetings-webservice, and this type keeps it that
 * way rather than pulling a REST-response-shaped concept (a free-text
 * message plus a SUCCESS/ERROR type) down into the manager/media-server
 * layer. The webservice layer maps this into its own wire DTO instead. No
 * JAXB annotations for the same reason -- this is never marshalled directly.
 */
public class SingleStreamRecordingStart {
	private final String requestId;
	private final long startTime;

	/**
	 * @param requestId the id needed to call
	 *                  {@link org.apache.openmeetings.db.manager.ISingleStreamRecordingManager#stopSingle(Long, String)}
	 *                  later
	 * @param startTime the instant this recording was activated, in the OM
	 *                  server's own wall clock -- milliseconds since the Unix
	 *                  epoch, i.e. the value of {@code System.currentTimeMillis()}
	 *                  at the moment {@code KStream.startSingleRecord} connected
	 *                  and activated the Kurento {@code RecorderEndpoint}. Same
	 *                  clock domain as the whiteboard recording log's own "ts"
	 *                  fields (see {@code WbRecordingManager} in
	 *                  openmeetings-service), so a caller synchronizing the two
	 *                  can compare them directly, with no timezone/offset
	 *                  conversion needed.
	 */
	public SingleStreamRecordingStart(String requestId, long startTime) {
		this.requestId = requestId;
		this.startTime = startTime;
	}

	public String getRequestId() {
		return requestId;
	}

	public long getStartTime() {
		return startTime;
	}
}
