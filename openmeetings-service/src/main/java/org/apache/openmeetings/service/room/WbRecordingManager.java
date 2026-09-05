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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.openmeetings.db.dao.file.FileItemDao;
import org.apache.openmeetings.db.entity.file.BaseFileItem;
import org.apache.openmeetings.util.NullStringer;
import org.apache.openmeetings.util.OmFileHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.openjson.JSONArray;
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

	/**
	 * Exports every file-backed whiteboard object referenced by this
	 * session's OWN recorded events (uploaded images/PDF pages/video posters
	 * -- anything that went through WbWebSocketHelper.sendWbFile/addFileUrl,
	 * identified by carrying a "fileId" -- NOT built-in clipart, which is
	 * placed entirely client-side, never goes through that path, and already
	 * uses a permanent bundled-asset path that needs no export at all) to
	 * durable local files alongside the JSONL log, and appends a manifest
	 * line mapping each object's uid to its exported path.
	 *
	 * Reads the file DIRECTLY off disk via fileDao, rather than fetching a
	 * URL over HTTP (an earlier version of this method did exactly that, and
	 * it's worth recording exactly why that failed rather than just changing
	 * it silently): RoomResourceReference.getFileItem() -- the handler
	 * behind every "room/file/{id}?ruid=..&wuid=.." URL these objects carry
	 * -- hard-requires `WebSession.get().isSignedIn()` plus a currently-
	 * connected Client resolved from the URL's own "uid" param
	 * (ClientManager.get(uid)). Confirmed live: a real PRESENTATION object's
	 * own logged URL, fetched by this class's own plain unauthenticated
	 * HttpClient moments after creation, got a clean 404 --
	 * "No file item was found" -- not a timing issue or a rotated ruid, but
	 * a structural one: a bare server-side HTTP client can never carry a
	 * signed-in Wicket session or correspond to a live connected client, so
	 * that URL is fundamentally unfetchable from outside a real browser
	 * session, at any point in the room's lifetime. Since this class already
	 * runs inside the same JVM as everything else, going around HTTP
	 * entirely and resolving the file the same way RoomResourceReference
	 * itself ultimately does -- fileDao.getAny(fileId) then
	 * BaseFileItem.getFile(ext) -- sidesteps the auth requirement completely
	 * (it's a plain DB lookup + a File on local disk, not a request).
	 *
	 * Also asks the live Whiteboards/Whiteboard state (Whiteboard.list()) for
	 * whichever file-backed objects the session's own log can't cover: one
	 * present in the OPENING SNAPSHOT (added via addFileToWb() in
	 * WbPanel.java -- e.g. a lesson PDF attached at room CREATION, which is
	 * how this actually happens in real production sessions, see
	 * tutorship_create_session_room()'s files=[{fileId,wbIdx}]) never
	 * appears as a createObj in this session's own log, so the log alone
	 * can't see it. An earlier version of this method skipped the live
	 * state entirely based on a real but narrower finding -- that a live
	 * object's stored JSON never carries a resolved "_src" URL, only
	 * "fileId" -- and mistakenly treated that as "fileId itself isn't
	 * there either". Checked directly against addFileToWb() (WbPanel.java):
	 * it calls wb.put(wuid, file) with a JSONObject that DOES include
	 * ATTR_FILE_ID, in the exact same shape a live createObj stores -- so
	 * Whiteboard.list() is a perfectly good fileId source, just never a
	 * _src source, which is fine since this method never reads _src at all
	 * (DAO lookup by fileId, not a URL fetch). Log-reconstructed items win
	 * on a uid collision (shouldn't occur -- a uid is never reused for a
	 * different object) so the live scan only ever fills in uids the log
	 * genuinely never saw.
	 *
	 * @param roomId room being stopped
	 * @param fileDao used to resolve each fileId to a real BaseFileItem and its
	 *                on-disk File -- the same DAO RoomResourceReference itself uses.
	 * @param liveItems every board's current Whiteboard.list() for this room, from
	 *                   the caller's own already-live Whiteboards handle -- fetching
	 *                   it again from in here would need a manager reference this
	 *                   class doesn't otherwise have any reason to hold.
	 */
	public static void exportAssets(Long roomId, FileItemDao fileDao, List<JSONObject> liveItems) {
		Session session = ACTIVE.get(roomId);
		if (session == null) {
			return;
		}
		Map<String, JSONObject> byUid = new HashMap<>();
		for (JSONObject item : reconstructFileBearingObjects(session.file)) {
			String uid = item.optString("uid", null);
			if (uid != null) {
				byUid.put(uid, item);
			}
		}
		if (liveItems != null) {
			for (JSONObject item : liveItems) {
				String uid = item.optString("uid", null);
				if (uid != null) {
					byUid.putIfAbsent(uid, item);
				}
			}
		}
		if (byUid.isEmpty()) {
			return;
		}
		File assetsDir = new File(OmFileHelper.getStreamsSubDir(roomId), "assets");
		if (!assetsDir.exists() && !assetsDir.mkdirs()) {
			log.warn("Could not create assets dir {}", assetsDir.getAbsolutePath());
			return;
		}
		markWorldTraversable(assetsDir);

		Map<String, Object> manifest = new HashMap<>();
		for (JSONObject item : byUid.values()) {
			String uid = item.optString("uid", null);
			long fileId = item.optLong("fileId", -1);
			if (uid == null || fileId < 0) {
				continue; // not file-backed (a drawn shape, text, math formula, clipart, ...)
			}
			try {
				BaseFileItem fi = fileDao.getAny(fileId);
				if (fi == null) {
					log.warn("Asset export: fileId {} (uid {}) not found for room {}", fileId, uid, roomId);
					continue;
				}
				if (fi.getType() == BaseFileItem.Type.PRESENTATION) {
					// A presentation is N separate page images -- fi.getFile(slide)
					// per page -- not one file. The client's own wb.js fetches every
					// page the same way (fabric.FabricImage.fromURL(_o._src +
					// '&slide=' + i) for i in [0,count)). Exporting only the item's
					// CURRENT slide, as this method used to, silently stranded every
					// other page -- fine for a single-page PDF, broken for the
					// multi-page lesson PDFs this whole feature is actually for.
					int count = Math.max(1, item.optInt("count", 1));
					JSONArray slidePaths = new JSONArray();
					for (int slide = 0; slide < count; slide++) {
						File src = fi.getFile(String.valueOf(slide));
						if (src == null || !src.exists()) {
							log.warn("Asset export: no on-disk file for fileId {} (uid {}) slide {}, room {}", fileId, uid, slide, roomId);
							continue;
						}
						File dest = new File(assetsDir, uid + "_" + slide + "." + extensionOf(src.getName()));
						Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
						markWorldReadable(dest);
						slidePaths.put("assets/" + dest.getName());
					}
					if (slidePaths.length() > 0) {
						manifest.put(uid, slidePaths);
					}
				} else {
					File src = fi.getFile(null);
					if (src == null || !src.exists()) {
						log.warn("Asset export: no on-disk file for fileId {} (uid {}), room {}", fileId, uid, roomId);
						continue;
					}
					File dest = new File(assetsDir, uid + "." + extensionOf(src.getName()));
					Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
					markWorldReadable(dest);
					manifest.put(uid, "assets/" + dest.getName());
				}
			} catch (Exception e) {
				log.error("Failed to export whiteboard asset for room {}, uid {}", roomId, uid, e);
			}
		}
		if (!manifest.isEmpty()) {
			JSONObject manifestJson = new JSONObject();
			for (Map.Entry<String, Object> e : manifest.entrySet()) {
				manifestJson.put(e.getKey(), e.getValue());
			}
			try {
				writeLine(session, new JSONObject()
						.put("type", "assets")
						.put("ts", System.currentTimeMillis())
						.put("manifest", manifestJson));
			} catch (IOException e) {
				log.error("Failed to write asset manifest for room {}", roomId, e);
			}
			log.info("Exported {} whiteboard asset(s) for room {}", manifest.size(), roomId);
		}
	}

	/**
	 * Replays this session's own JSONL log (createObj/modifyObj/deleteObj
	 * only -- setSlide/createWb/activateWb/setSize carry no object state)
	 * to reconstruct the last-known JSON for every file-bearing object
	 * CREATED DURING this recorded session. modifyObj MERGES into whatever's
	 * already known for that uid (a partial update, matching how the client
	 * applies it) rather than replacing wholesale, so a later resize/move of
	 * a file object doesn't wipe out the fileId its own createObj already
	 * captured.
	 *
	 * NOT reconstructed here: an object already on the board when recording
	 * STARTED (present in the opening snapshot) never appears as a createObj
	 * in this session's own log, so this method alone can't see it -- this
	 * is in fact the NORMAL case for a lesson PDF, which gets attached at
	 * ROOM CREATION (addFileToWb() in WbPanel.java, driven by
	 * tutorship_create_session_room()'s files=[{fileId,wbIdx}]), before
	 * recording ever starts. The caller covers this gap by also passing in
	 * the room's live Whiteboard.list() state -- see exportAssets()'s own
	 * doc comment for why that's a safe, sufficient source for fileId
	 * despite not carrying a resolved _src.
	 */
	private static List<JSONObject> reconstructFileBearingObjects(File logFile) {
		Map<String, JSONObject> latestByUid = new HashMap<>();
		List<String> lines;
		try {
			lines = Files.readAllLines(logFile.toPath(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			log.error("Failed to re-read whiteboard log for asset export: {}", logFile.getAbsolutePath(), e);
			return List.of();
		}
		for (String line : lines) {
			JSONObject decoded;
			try {
				decoded = new JSONObject(line);
			} catch (Exception e) {
				continue;
			}
			if (!"event".equals(decoded.optString("type", null))) {
				continue;
			}
			String func = decoded.optString("func", null);
			JSONObject param = decoded.optJSONObject("param");
			JSONObject obj = param == null ? null : param.optJSONObject("obj");
			String uid = obj == null ? null : obj.optString("uid", null);
			if (uid == null) {
				continue;
			}
			switch (func == null ? "" : func) {
				case "createObj":
					latestByUid.put(uid, obj);
					break;
				case "modifyObj":
					JSONObject existing = latestByUid.get(uid);
					if (existing == null) {
						latestByUid.put(uid, obj);
					} else {
						JSONObject merged = new JSONObject(existing.toString());
						for (String key : obj.keySet()) {
							merged.put(key, obj.get(key));
						}
						latestByUid.put(uid, merged);
					}
					break;
				case "deleteObj":
					latestByUid.remove(uid);
					break;
				default:
					break;
			}
		}
		return new ArrayList<>(latestByUid.values());
	}

	private static String extensionOf(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot < 0 || dot == fileName.length() - 1 ? "bin" : fileName.substring(dot + 1);
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
