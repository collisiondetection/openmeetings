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
import java.io.InputStream;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
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

	/**
	 * Exports any room/session-scoped file asset referenced by the room's
	 * current board items (uploaded images/PDF pages/video posters -- anything
	 * that went through WbWebSocketHelper.sendWbFile/addFileUrl, NOT built-in
	 * clipart, which already uses a permanent static path) to durable local
	 * files alongside the JSONL log, and appends a manifest line mapping each
	 * object's uid to its exported path.
	 *
	 * Must run while the room is still live: addFileUrl() builds these URLs
	 * with the room's current Whiteboards session uid (ruid) baked in via
	 * Wicket's own RequestCycle-bound URL rendering, which this class has no
	 * access to (it isn't a Wicket request) and doesn't need -- by the time an
	 * event reaches record(), the URL is already a plain, fully-resolved
	 * string. Fetching an already-built URL is just HTTP; only building a NEW
	 * one needs Wicket. Once the room rotates or this session's Whiteboards
	 * entry is evicted, that ruid stops resolving -- this is the one window
	 * where it's guaranteed to still work, which is why this runs at stop(),
	 * not later.
	 *
	 * @param roomId room being stopped
	 * @param items current board objects (e.g. Whiteboard.list() per board)
	 * @param selfBaseUrl this OM instance's own origin, e.g. "http://localhost:5080" --
	 *                     used only when a found src is context-relative, not absolute.
	 */
	public static void exportAssets(Long roomId, List<JSONObject> items, String selfBaseUrl) {
		Session session = ACTIVE.get(roomId);
		if (session == null || items == null || items.isEmpty()) {
			return;
		}
		File assetsDir = new File(OmFileHelper.getStreamsSubDir(roomId), "assets");
		if (!assetsDir.exists() && !assetsDir.mkdirs()) {
			log.warn("Could not create assets dir {}", assetsDir.getAbsolutePath());
			return;
		}
		markWorldTraversable(assetsDir);

		Map<String, String> manifest = new HashMap<>();
		RequestConfig timeout = RequestConfig.custom()
				.setConnectTimeout(10_000).setSocketTimeout(10_000).build();
		try (CloseableHttpClient client = HttpClients.custom().setDefaultRequestConfig(timeout).build()) {
			for (JSONObject item : items) {
				String uid = item.optString("uid", null);
				// Prefer the OM-authored "_src" (wb.js's own extraProps whitelist --
				// for Clipart it's the clean relative path passed to fabric's
				// Image.fromURL(), before the browser resolves it) over plain "src"
				// (a standard fabric.js Image property that ends up holding
				// whatever host:port the BROWSER resolved it to -- correct for that
				// browser's tab, meaningless to this JVM's own loopback fetch).
				String src = firstNonEmpty(item.optString("_src", null), item.optString("src", null));
				if (uid == null || src == null) {
					continue;
				}
				String url = toFetchableUrl(src, selfBaseUrl);
				try (CloseableHttpResponse resp = client.execute(new HttpGet(url))) {
					int status = resp.getStatusLine().getStatusCode();
					if (status != 200) {
						log.warn("Asset export got HTTP {} for uid {} url {}", status, uid, url);
						continue;
					}
					HttpEntity entity = resp.getEntity();
					String contentType = entity.getContentType() != null ? entity.getContentType().getValue() : "";
					File dest = new File(assetsDir, uid + "." + extensionFor(contentType));
					try (InputStream in = entity.getContent()) {
						Files.copy(in, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
					}
					markWorldReadable(dest);
					manifest.put(uid, "assets/" + dest.getName());
				} catch (Exception e) {
					log.error("Failed to export whiteboard asset for room {}, uid {}", roomId, uid, e);
				}
			}
		} catch (IOException e) {
			log.error("Failed to export whiteboard assets for room {}", roomId, e);
			return;
		}
		if (!manifest.isEmpty()) {
			JSONObject manifestJson = new JSONObject();
			for (Map.Entry<String, String> e : manifest.entrySet()) {
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

	private static String firstNonEmpty(String a, String b) {
		return (a != null && !a.isEmpty()) ? a : b;
	}

	/**
	 * Resolves any src value (relative, or absolute-but-pointing-at-whatever-host
	 * the browser that created it happened to be on) to a URL this JVM can
	 * actually fetch over its own loopback -- the authority (scheme+host+port)
	 * always comes from selfBaseUrl, never from an absolute src, since a browser's
	 * own externally-visible address is frequently NOT this server's internal one
	 * (true in this project's own production topology: TLS-terminated public
	 * hostname vs. the OM JVM's internal loopback port -- the exact case that
	 * surfaced this as a real Connection Refused, not just a defensive guess).
	 */
	private static String toFetchableUrl(String rawSrc, String selfBaseUrl) {
		try {
			if (rawSrc.startsWith("http")) {
				URI src = new URI(rawSrc);
				URI base = new URI(selfBaseUrl);
				String pathAndQuery = src.getRawPath() + (src.getRawQuery() != null ? "?" + src.getRawQuery() : "");
				return base.getScheme() + "://" + base.getAuthority() + pathAndQuery;
			}
			String rel = rawSrc.startsWith("./") ? rawSrc.substring(1) : (rawSrc.startsWith("/") ? rawSrc : "/" + rawSrc);
			return selfBaseUrl + rel;
		} catch (URISyntaxException e) {
			return rawSrc;
		}
	}

	private static String extensionFor(String contentType) {
		if (contentType.contains("png")) {
			return "png";
		} else if (contentType.contains("jpeg") || contentType.contains("jpg")) {
			return "jpg";
		} else if (contentType.contains("pdf")) {
			return "pdf";
		} else if (contentType.contains("video")) {
			return "mp4";
		}
		return "bin";
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
