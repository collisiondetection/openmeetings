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
package org.apache.openmeetings.mediaserver;

import static java.util.UUID.randomUUID;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.openmeetings.db.entity.basic.Client;
import org.apache.openmeetings.db.entity.basic.Client.Activity;
import org.apache.openmeetings.db.entity.basic.WebcamStreamDesc;
import org.apache.openmeetings.db.manager.IClientManager;
import org.apache.openmeetings.db.manager.ISingleStreamRecordingManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/**
 * Placed in the same package as {@link StreamProcessor}/{@link KStream}
 * deliberately, so it can call their package-private accessors
 * ({@code StreamProcessor.getByUid}, {@code KurentoHandler.isConnected})
 * without widening any existing visibility.
 *
 * Same interface-in-db/impl-in-mediaserver split as
 * {@link org.apache.openmeetings.db.manager.IClientManager}/
 * {@link org.apache.openmeetings.db.manager.IWhiteboardManager} --
 * openmeetings-webservice cannot depend on openmeetings-mediaserver
 * directly (the module graph runs the other way), so it depends on the
 * interface and Spring wires this implementation in.
 */
@Singleton
@Named
public class SingleStreamRecordingManager implements ISingleStreamRecordingManager {
	private static final Logger log = LoggerFactory.getLogger(SingleStreamRecordingManager.class);

	@Inject
	private KurentoHandler kHandler;
	@Inject
	private StreamProcessor processor;
	@Inject
	private IClientManager cm;

	// requestId -> the target stream's own uid, so a stop call can re-resolve
	// the CURRENT live KStream for that uid at stop time rather than holding
	// a KStream reference directly, which could go stale across a reconnect.
	// In-JVM only -- same single-node caveat WbRecordingManager already
	// documents and accepts; this capability shares that limitation, not a
	// new one.
	private final Map<String, String> streamUidByRequestId = new ConcurrentHashMap<>();

	@Override
	public String startSingle(Long roomId, String externalUserId) {
		if (!kHandler.isConnected()) {
			throw new IllegalStateException("Media server is not connected");
		}
		List<Client> matches = cm.streamByRoom(roomId)
				.filter(c -> c.getUser() != null && externalUserId.equals(c.getUser().getExternalId()))
				.toList();
		if (matches.isEmpty()) {
			throw new IllegalStateException("No participant with externalId " + externalUserId + " in room " + roomId);
		}
		if (matches.size() > 1) {
			throw new IllegalStateException("Ambiguous: " + matches.size() + " participants with externalId " + externalUserId + " in room " + roomId);
		}
		Client c = matches.get(0);
		List<WebcamStreamDesc> camStreams = c.getCamStreams().toList();
		if (camStreams.isEmpty()) {
			throw new IllegalStateException("Participant " + externalUserId + " has no active webcam stream in room " + roomId);
		}
		if (camStreams.size() > 1) {
			throw new IllegalStateException("Ambiguous: participant " + externalUserId + " has " + camStreams.size() + " active webcam streams in room " + roomId);
		}
		WebcamStreamDesc sd = camStreams.get(0);
		if (!sd.has(Activity.VIDEO)) {
			throw new IllegalStateException("Participant " + externalUserId + "'s stream has no video in room " + roomId);
		}
		KStream stream = processor.getByUid(sd.getUid());
		if (stream == null) {
			throw new IllegalStateException("Stream " + sd.getUid() + " is not ready in room " + roomId);
		}
		String requestId = randomUUID().toString();
		if (!stream.startSingleRecord(requestId)) {
			throw new IllegalStateException("Stream " + sd.getUid() + " refused to start single-stream recording (already recording, or no media) in room " + roomId);
		}
		streamUidByRequestId.put(requestId, sd.getUid());
		log.info("Started single-stream recording, room {}, externalUserId {}, requestId {}", roomId, externalUserId, requestId);
		return requestId;
	}

	@Override
	public void stopSingle(Long roomId, String requestId) {
		String streamUid = streamUidByRequestId.remove(requestId);
		if (streamUid == null) {
			log.info("stopSingle: no in-progress recording for requestId {} in room {} -- already stopped or never started", requestId, roomId);
			return;
		}
		KStream stream = processor.getByUid(streamUid);
		if (stream == null) {
			// The participant already disconnected, which independently calls
			// KStream.release() -> stopSingleRecord() -- the file is already
			// finalized, there is simply nothing left here to stop.
			log.info("stopSingle: stream {} (requestId {}) is already gone in room {}", streamUid, requestId, roomId);
			return;
		}
		stream.stopSingleRecord();
		log.info("Stopped single-stream recording, room {}, requestId {}", roomId, requestId);
	}
}
