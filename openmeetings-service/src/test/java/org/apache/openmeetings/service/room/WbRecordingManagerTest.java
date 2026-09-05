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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.apache.openmeetings.db.dao.file.FileItemDao;
import org.apache.openmeetings.db.entity.file.BaseFileItem;
import org.apache.openmeetings.util.OmFileHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.openjson.JSONArray;
import com.github.openjson.JSONObject;

/**
 * Regression test for the real gap found and fixed 2026-09-05: a lesson PDF is
 * attached at ROOM CREATION (addFileToWb() in WbPanel.java, driven by
 * tutorship_create_session_room()'s files=[{fileId,wbIdx}]) -- before whiteboard
 * recording ever starts, so it's present in the opening snapshot and NEVER
 * appears as a createObj in the session's own JSONL log. The original
 * exportAssets() only ever looked at that log, so every real production lesson
 * PDF silently failed to export at all. Fixed by also merging in the room's
 * live Whiteboard.list() state, and -- since a real lesson PDF is normally
 * multi-page -- exporting every page instead of just whichever slide happened
 * to be current.
 *
 * Isolated from any real OM install via OmFileHelper.setOmHome() pointing at a
 * throwaway temp directory, and a high, unlikely-to-collide room id -- this
 * never touches a real streams/ directory.
 */
class WbRecordingManagerTest {
	private static final Long ROOM_ID = 999999L;
	private File tempHome;

	@BeforeEach
	void setUp() throws Exception {
		tempHome = Files.createTempDirectory("wbrec-test").toFile();
		OmFileHelper.setOmHome(tempHome);
	}

	@AfterEach
	void tearDown() throws Exception {
		if (WbRecordingManager.isRecording(ROOM_ID)) {
			WbRecordingManager.stop(ROOM_ID);
		}
		deleteRecursively(tempHome);
	}

	private static void deleteRecursively(File f) {
		File[] kids = f.listFiles();
		if (kids != null) {
			for (File k : kids) {
				deleteRecursively(k);
			}
		}
		f.delete();
	}

	@Test
	void exportsEveryPageOfAPresentationPresentOnlyInLiveSnapshot() throws Exception {
		// Opening snapshot with NO boards/objects -- matches the real gap: the
		// presentation is never a createObj in this session's own log.
		WbRecordingManager.start(ROOM_ID, new JSONObject().put("activeWb", 0).put("boards", new JSONObject()));

		// Shape verified directly against addFileToWb() (WbPanel.java:562-573) --
		// fileId/fileType/count/uid/slide, deliberately no _src (never persisted
		// there for ANY file object, live or logged -- exportAssets() never reads
		// it, resolving fileId via the DAO instead).
		JSONObject presentationItem = new JSONObject()
				.put("uid", "pres-uid-1")
				.put("fileId", 4L)
				.put("fileType", "PRESENTATION")
				.put("count", 2)
				.put("type", "image")
				.put("slide", 0);
		List<JSONObject> liveItems = new ArrayList<>();
		liveItems.add(presentationItem);

		File page0 = new File(tempHome, "page0.jpg");
		File page1 = new File(tempHome, "page1.jpg");
		Files.writeString(page0.toPath(), "fake-page-0-bytes");
		Files.writeString(page1.toPath(), "fake-page-1-bytes");

		BaseFileItem fi = mock(BaseFileItem.class);
		when(fi.getType()).thenReturn(BaseFileItem.Type.PRESENTATION);
		when(fi.getFile("0")).thenReturn(page0);
		when(fi.getFile("1")).thenReturn(page1);

		FileItemDao fileDao = mock(FileItemDao.class);
		when(fileDao.getAny(4L)).thenReturn(fi);

		WbRecordingManager.exportAssets(ROOM_ID, fileDao, liveItems);
		WbRecordingManager.stop(ROOM_ID);

		File streamsDir = OmFileHelper.getStreamsSubDir(ROOM_ID);
		File[] logFiles = streamsDir.listFiles((d, name) -> name.startsWith("wb_") && name.endsWith(".jsonl"));
		assertNotNull(logFiles);
		assertEquals(1, logFiles.length, "expected exactly one whiteboard log file");

		JSONObject assetsLine = null;
		for (String line : Files.readAllLines(logFiles[0].toPath(), StandardCharsets.UTF_8)) {
			JSONObject decoded = new JSONObject(line);
			if ("assets".equals(decoded.optString("type", null))) {
				assetsLine = decoded;
			}
		}
		assertNotNull(assetsLine, "no assets manifest line was written -- the live-state object was never seen at all");

		JSONObject manifest = assetsLine.getJSONObject("manifest");
		assertTrue(manifest.has("pres-uid-1"),
				"manifest is missing the presentation entirely -- this IS the original bug: "
				+ "a file object present only in the opening snapshot, never reconstructed from the log");

		JSONArray slides = manifest.getJSONArray("pres-uid-1");
		assertEquals(2, slides.length(),
				"expected both pages exported, not just whichever slide happened to be current -- "
				+ "exporting only page 0 was the second, narrower bug in the same method");

		for (int i = 0; i < slides.length(); i++) {
			File exported = new File(streamsDir, slides.getString(i));
			assertTrue(exported.exists(), "exported slide file missing on disk: " + exported);
			assertTrue(exported.length() > 0, "exported slide file is empty: " + exported);
		}
	}
}
