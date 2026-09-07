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

import static org.apache.openmeetings.util.OmFileHelper.getName;
import static org.apache.openmeetings.util.OmFileHelper.getRecordingChunk;
import static org.apache.openmeetings.util.OmFileHelper.getStreamsSubDir;
import static org.apache.openmeetings.util.OpenmeetingsVariables.CONFIG_PATH_FFMPEG;
import static org.apache.openmeetings.util.OpenmeetingsVariables.getAudioBitrate;
import static org.apache.openmeetings.util.OpenmeetingsVariables.getAudioRate;
import static org.apache.openmeetings.util.OpenmeetingsVariables.getVideoPreset;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.openmeetings.db.dao.basic.ConfigurationDao;
import org.apache.openmeetings.util.process.ProcessHelper;
import org.apache.openmeetings.util.process.ProcessResult;
import org.apache.wicket.util.string.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.inject.Inject;

/**
 * Converts the raw Kurento chunk {@link org.apache.openmeetings.mediaserver.KStream#startSingleRecord(String)}
 * produces into a playable mp4 -- deliberately NOT part of OM's normal
 * recording-registration pipeline ({@link org.apache.openmeetings.db.dao.record.RecordingChunkDao}/
 * {@code Recording}), since a single-participant recording has neither.
 *
 * Reuses whatever {@code path.ffmpeg} already resolves to -- in production
 * that is {@code terraform/templates/ffmpeg-batch-submit.sh}, deployed under
 * the name "ffmpeg" so OM's own {@code BaseConverter} (and now this class)
 * invoke it transparently, with no code-level AWS Batch integration needed
 * here at all: the shim already submits, polls, and falls back to local
 * conversion on any non-ffmpeg failure. The only thing this class adds on
 * top is telling that shim which job DEFINITION to use -- see
 * {@code SINGLE_STREAM_BATCH_JOBDEF} below -- since its own default targets
 * the main (larger, multi-track) conversion job. In a dev/test environment
 * with no shim installed, {@code path.ffmpeg} resolves to the real ffmpeg
 * binary and this env var is simply ignored.
 */
@Component
public class SingleStreamConversionSubmitter {
	private static final Logger log = LoggerFactory.getLogger(SingleStreamConversionSubmitter.class);

	// Deployment-time, not an OM admin setting -- matches how every other
	// per-environment value this shim/container already needs (OM_DB_HOST,
	// OM_KURENTO_WS_URL, ...) is supplied: a container env var, not a new
	// row in OM's own configuration table. Left unset, the shim falls back
	// to ITS OWN default (the main conversion job) -- degraded (wrong-sized
	// compute, shared cost/log accounting) but not broken, so a deployment
	// that forgets to set this does not lose conversions over it.
	private static final String JOBDEF_ENV_VAR = "SINGLE_STREAM_BATCH_JOBDEF";

	@Inject
	private ConfigurationDao cfgDao;

	private String getPathToFFMPEG() {
		final String cfg = cfgDao.getString(CONFIG_PATH_FFMPEG, "");
		StringBuilder path = new StringBuilder(cfg);
		if (!Strings.isEmpty(cfg) && !cfg.endsWith(File.separator)) {
			path.append(File.separator);
		}
		return path.append("ffmpeg").toString();
	}

	/**
	 * @param roomId room the recording was made in
	 * @param requestId the id {@link org.apache.openmeetings.db.manager.ISingleStreamRecordingManager#startSingle}
	 *                  returned, and the raw chunk is named after
	 * @return the finalized mp4's own {@link File}, or {@code null} if
	 *         conversion failed (logged at error level either way -- a
	 *         failed conversion is not the caller's problem to react to
	 *         beyond that, the raw webm chunk is untouched either way)
	 */
	public File convert(Long roomId, String requestId) {
		File webm = getRecordingChunk(roomId, "single_" + requestId);
		if (!webm.exists()) {
			log.error("Single-stream chunk missing, room {}, requestId {}, expected at {}", roomId, requestId, webm);
			return null;
		}
		File outDir = new File(getStreamsSubDir(roomId), "single");
		if (!outDir.exists() && !outDir.mkdirs()) {
			log.error("Could not create single-stream output dir {}", outDir);
			return null;
		}
		File finalMp4 = new File(outDir, getName(requestId, "mp4"));
		File partMp4 = new File(outDir, getName(requestId, "mp4.part"));

		List<String> argv = new ArrayList<>(List.of(getPathToFFMPEG(), "-y", "-i", webm.getAbsolutePath()));
		argv.addAll(List.of(
				"-c:v", "h264"
				, "-crf", "24"
				, "-vsync", "0"
				, "-pix_fmt", "yuv420p"
				, "-preset", getVideoPreset()
				, "-profile:v", "baseline"
				, "-level", "3.0"
				, "-movflags", "faststart"
				, "-c:a", "aac"
				, "-ar", String.valueOf(getAudioRate())
				, "-b:a", getAudioBitrate()
				, "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2"
				// ffmpeg picks its output container by reading the OUTPUT
				// filename's extension -- ".mp4.part" reads as the unknown
				// extension ".part" and fails outright ("Unable to choose an
				// output format"), found live. Naming the format explicitly
				// keeps the .part suffix (needed for the atomic rename below)
				// without depending on ffmpeg's own extension sniffing.
				, "-f", "mp4"
				));
		argv.add(partMp4.getAbsolutePath());

		String jobDef = System.getenv(JOBDEF_ENV_VAR);
		Map<String, String> env = (jobDef == null || jobDef.isEmpty()) ? Map.of() : Map.of("BATCH_JOBDEF", jobDef);
		ProcessResult result = ProcessHelper.exec("single-stream-convert", argv, env);
		if (!result.isOk()) {
			log.error("Single-stream conversion failed, room {}, requestId {}: {}", roomId, requestId, result.buildLogMessage());
			partMp4.delete();
			return null;
		}
		// Atomic rename -- "file exists at the final name" is the caller's
		// unambiguous, race-free completion signal, matching the raw chunk's
		// own already-finalized-by-stopAndWait guarantee.
		if (!partMp4.renameTo(finalMp4)) {
			log.error("Could not rename {} to {}", partMp4, finalMp4);
			return null;
		}
		log.info("Single-stream conversion done, room {}, requestId {} -> {}", roomId, requestId, finalMp4);
		return finalMp4;
	}
}
