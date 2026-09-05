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
package org.apache.openmeetings.service.room;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.openmeetings.util.NullStringer;
import org.apache.openmeetings.util.OmFileHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.openjson.JSONObject;

/**
 * Persists the whiteboard's server-side event stream to a JSONL file per room,
 * independent of OM's own StreamProcessor/A-V recording. Every mutating whiteboard
 * action already funnels through {@code WbWebSocketHelper} (openmeetings-web) before
 * being broadcast to clients; that class calls {@link #record(Long, String, JSONObject)}
 * for each one. Deliberately static/stateless-except-map, mirroring WbWebSocketHelper's
 * own style, so it needs no Spring wiring to be reachable from both the web (caller)
 * and webservice (start/stop trigger) modules -- see openmeetings-service's position
 * below both in the module graph.
 *
 * Single-node only: recording state lives in this JVM's memory, not Hazelcast. A
 * clustered deployment would need one node designated per room, since WbWebSocketHelper's
 * sendWb() runs on every node holding a connection for the room.
 */
public final class WbRecordingManager {
	private static final Logger log = LoggerFactory.getLogger(WbRecordingManager.class);
	private static final String WB_LOG_PREFIX = "wb_";
	private static final String WB_LOG_EXT = "jsonl";

	private static final Map<Long, Session> ACTIVE = new ConcurrentHashMap<>();

	private WbRecordingManager() {
		// denied
	}

	private static final class Session {
		private final Writer writer;
		private final File file;

		Session(Writer writer, File file) {
			this.writer = writer;
			this.file = file;
		}
	}

	public static boolean isRecording(Long roomId) {
		return ACTIVE.containsKey(roomId);
	}

	public static synchronized String start(Long roomId, JSONObject snapshot) throws IOException {
		if (ACTIVE.containsKey(roomId)) {
			throw new IllegalStateException("Whiteboard recording already active for room " + roomId);
		}
		File dir = OmFileHelper.getStreamsSubDir(roomId);
		File file = new File(dir, OmFileHelper.getName(WB_LOG_PREFIX + System.currentTimeMillis(), WB_LOG_EXT));
		Session session = new Session(Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8), file);
		// World-readable/traversable: this log is written for a different consumer
		// (Moodle, reading it from a different container over a shared volume) to
		// read, not just this JVM. The default create mode (umask-dependent -- seen
		// 750 on the directories, 640 on the file, all owned by this process's own
		// user in testing) left the whole path unreadable cross-container: a
		// world-readable file is still unreachable behind a non-world-executable
		// parent directory, so both need fixing, and getStreamsSubDir()'s parent
		// (streams/) is created the same restrictive way on first use too.
		markWorldReadable(file);
		markWorldTraversable(dir);
		markWorldTraversable(dir.getParentFile());
		writeLine(session, new JSONObject()
				.put("type", "snapshot")
				.put("ts", System.currentTimeMillis())
				.put("snapshot", snapshot));
		ACTIVE.put(roomId, session);
		log.info("Whiteboard recording started for room {}: {}", roomId, file.getAbsolutePath());
		return file.getAbsolutePath();
	}

	public static synchronized void stop(Long roomId) throws IOException {
		Session session = ACTIVE.remove(roomId);
		if (session == null) {
			return;
		}
		synchronized (session) {
			session.writer.close();
		}
		log.info("Whiteboard recording stopped for room {}: {}", roomId, session.file.getAbsolutePath());
	}

	public static void record(Long roomId, String func, JSONObject param) {
		Session session = ACTIVE.get(roomId);
		if (session == null) {
			return;
		}
		try {
			writeLine(session, new JSONObject()
					.put("type", "event")
					.put("ts", System.currentTimeMillis())
					.put("func", func)
					.put("param", param));
		} catch (IOException e) {
			log.error("Failed to persist whiteboard event for room {}, func {}", roomId, func, e);
		}
	}

	private static void writeLine(Session session, JSONObject line) throws IOException {
		synchronized (session) {
			session.writer.write(line.toString(new NullStringer()));
			session.writer.write('\n');
			session.writer.flush();
		}
	}

	private static void markWorldReadable(File f) {
		if (!f.setReadable(true, false)) {
			log.warn("Could not make world-readable: {}", f.getAbsolutePath());
		}
	}

	private static void markWorldTraversable(File dir) {
		markWorldReadable(dir);
		if (!dir.setExecutable(true, false)) {
			log.warn("Could not make world-traversable: {}", dir.getAbsolutePath());
		}
	}
}
