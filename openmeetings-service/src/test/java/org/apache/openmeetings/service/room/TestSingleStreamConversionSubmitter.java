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

import static org.apache.openmeetings.util.OpenmeetingsVariables.CONFIG_PATH_FFMPEG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.openmeetings.db.dao.basic.ConfigurationDao;
import org.apache.openmeetings.util.OmFileHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/**
 * Regression tests for finding CONV-1: the {@code single/} output directory
 * of {@link SingleStreamConversionSubmitter#convert(Long, String)} used to be
 * created with an {@code exists()}/{@code mkdirs()} check-then-act pair.
 *
 * A room's teacher and student stops are dispatched 0-1ms apart by the batch
 * stop caller, so both threads reached that pair together: the loser's
 * {@code mkdirs()} returned {@code false} purely because the winner had just
 * created the directory, and the loser's (perfectly convertible) recording was
 * reported as "Recording stopped but conversion failed" -- 2 of 100 and 8 of
 * 60 recordings in two real staging load tests.
 *
 * Two levels are pinned here on purpose:
 * <ul>
 * <li>the extracted {@code ensureOutputDir()} helper, hammered by many threads
 *     released together by a barrier -- a deterministic, high-strength race;</li>
 * <li>{@code convert()} itself, driven for real (real {@code ProcessHelper},
 *     a fake {@code ffmpeg} script) with pairs of same-room stops -- this is
 *     what proves {@code convert()} actually goes through the race-free
 *     helper rather than some inlined racy copy of it.</li>
 * </ul>
 *
 * The {@code convert()} assertions are CAUSAL, not just "it returned null":
 * a capturing logback appender is attached to the submitter's logger, and the
 * test asserts there is not one {@value #CONV1_MESSAGE} ERROR event. A
 * {@code null} return for any OTHER reason (a process or thread cap while
 * spawning the child processes, an exec failure, ...) is reported separately,
 * with the other logged errors, so an environment limit is never misreported
 * as a CONV-1 regression. The "no such event" check cannot pass vacuously:
 * the same run must also capture one "conversion done" INFO event per call,
 * and a separate test proves an ERROR event of exactly that wording is
 * captured when the directory genuinely cannot be created.
 *
 * Everything runs against a throwaway temp directory via
 * {@link OmFileHelper#setOmHome(File)}, which mutates STATIC state shared by
 * every test class in the surefire JVM -- so the previous values are saved and
 * restored around every test, and a {@code DATA_DIR} system property (which
 * {@code setOmHome} would otherwise honour, pointing the streams directory at
 * a real data dir) is cleared for the duration and put back afterwards. No
 * real OM install is touched, and nothing leaks into other test classes.
 */
class TestSingleStreamConversionSubmitter {
	private static final int HAMMER_THREADS = 48;
	private static final int HAMMER_ROUNDS = 300;

	private static final int CONVERT_ROOMS_PER_ROUND = 24;
	private static final int CONVERT_ROUNDS = 6;

	/** The exact wording of the CONV-1 error log in {@code ensureOutputDir()}. */
	private static final String CONV1_MESSAGE = "Could not create single-stream output dir";
	private static final String DONE_MESSAGE = "Single-stream conversion done";

	/** Thread-safe: convert() is logged from dozens of threads at once. */
	private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {
		private final Queue<ILoggingEvent> events = new ConcurrentLinkedQueue<>();

		@Override
		protected void append(ILoggingEvent event) {
			events.add(event);
		}
	}

	private CapturingAppender appender;
	private Logger submitterLogger;
	private Level savedLoggerLevel;
	private boolean savedLoggerAdditive;
	private Object savedOmHome;
	private Object savedDataHome;
	private String savedDataDirProperty;

	@BeforeEach
	void isolateGlobalStateAndCaptureLogs() {
		// OmFileHelper keeps omHome/dataHome in private statics with no getter
		// for dataHome (and getOmHome() may be null), so save the raw fields in
		// order to restore them exactly, null included.
		savedOmHome = ReflectionTestUtils.getField(OmFileHelper.class, "omHome");
		savedDataHome = ReflectionTestUtils.getField(OmFileHelper.class, "dataHome");
		// setOmHome() honours DATA_DIR; if it were set the streams directory
		// would point at a real data dir and the tests would write into it.
		savedDataDirProperty = System.getProperty("DATA_DIR");
		System.clearProperty("DATA_DIR");

		assertTrue(LoggerFactory.getLogger(SingleStreamConversionSubmitter.class) instanceof Logger
				, "the slf4j binding is not logback, so the causal log assertions cannot work: "
						+ LoggerFactory.getLogger(SingleStreamConversionSubmitter.class).getClass());
		submitterLogger = (Logger) LoggerFactory.getLogger(SingleStreamConversionSubmitter.class);
		savedLoggerLevel = submitterLogger.getLevel();
		savedLoggerAdditive = submitterLogger.isAdditive();
		appender = new CapturingAppender();
		appender.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
		appender.start();
		// INFO so the "conversion done" events are captured too (see the
		// non-vacuity check); non-additive so ~300 INFO lines do not flood the
		// surefire console. Errors are quoted in the assertion messages instead.
		submitterLogger.setLevel(Level.INFO);
		submitterLogger.setAdditive(false);
		submitterLogger.addAppender(appender);
	}

	@AfterEach
	void restoreGlobalStateAndLogs() {
		submitterLogger.detachAppender(appender);
		appender.stop();
		submitterLogger.setAdditive(savedLoggerAdditive);
		submitterLogger.setLevel(savedLoggerLevel);

		ReflectionTestUtils.setField(OmFileHelper.class, "omHome", savedOmHome);
		ReflectionTestUtils.setField(OmFileHelper.class, "dataHome", savedDataHome);
		if (savedDataDirProperty == null) {
			System.clearProperty("DATA_DIR");
		} else {
			System.setProperty("DATA_DIR", savedDataDirProperty);
		}
	}

	private List<ILoggingEvent> eventsAt(Level level, String messagePart) {
		List<ILoggingEvent> found = new ArrayList<>();
		for (ILoggingEvent event : appender.events) {
			if (event.getLevel() == level && event.getFormattedMessage().contains(messagePart)) {
				found.add(event);
			}
		}
		return found;
	}

	/** Every ERROR event that is NOT the CONV-1 one, as readable strings. */
	private List<String> otherErrors() {
		List<String> found = new ArrayList<>();
		for (ILoggingEvent event : appender.events) {
			if (event.getLevel() == Level.ERROR && !event.getFormattedMessage().contains(CONV1_MESSAGE)) {
				found.add(event.getFormattedMessage());
			}
		}
		return found;
	}

	/**
	 * Points {@link OmFileHelper} at the temp dir and builds a submitter whose
	 * {@code ffmpeg} is a fake script: it writes a marker into whatever the
	 * LAST argv entry is (convert() always passes the {@code .mp4.part} output
	 * last) and exits 0.
	 */
	private SingleStreamConversionSubmitter newSubmitterWithFakeFfmpeg(Path tmp) throws Exception {
		OmFileHelper.setOmHome(tmp.resolve("om").toFile());
		// Guard: nothing this test writes may land outside the temp dir.
		assertTrue(OmFileHelper.getStreamsDir().toPath().startsWith(tmp)
				, "the streams dir must live under the temp dir, but is " + OmFileHelper.getStreamsDir());

		Path binDir = Files.createDirectories(tmp.resolve("bin"));
		File fakeFfmpeg = binDir.resolve("ffmpeg").toFile();
		Files.writeString(fakeFfmpeg.toPath()
				, "#!/bin/sh\nfor a in \"$@\"; do last=\"$a\"; done\necho converted > \"$last\"\nexit 0\n"
				, StandardCharsets.UTF_8);
		assertTrue(fakeFfmpeg.setExecutable(true, false), "could not mark the fake ffmpeg executable");
		// Fail with a precise message if scripts cannot run from the temp dir
		// (e.g. a noexec mount) instead of surfacing as a bogus "CONV-1" failure.
		File probeOut = tmp.resolve("probe.out").toFile();
		Process probe = new ProcessBuilder(fakeFfmpeg.getAbsolutePath(), probeOut.getAbsolutePath()).start();
		assertEquals(0, probe.waitFor(), "the fake ffmpeg script cannot be executed from " + binDir + " (noexec temp dir?)");
		assertTrue(probeOut.isFile(), "the fake ffmpeg script ran but did not write its output file");

		ConfigurationDao cfgDao = mock(ConfigurationDao.class);
		when(cfgDao.getString(CONFIG_PATH_FFMPEG, "")).thenReturn(binDir.toString());
		SingleStreamConversionSubmitter submitter = new SingleStreamConversionSubmitter();
		ReflectionTestUtils.setField(submitter, "cfgDao", cfgDao);
		return submitter;
	}

	/**
	 * Releases {@link #HAMMER_THREADS} threads at once against a fresh,
	 * not-yet-existing directory, {@code rounds} times over (a new directory
	 * every round, since only the FIRST creation is racy).
	 *
	 * @return how many individual {@code ensureOutputDir()} calls reported
	 *         failure (should always be 0)
	 */
	private static int hammer(Path base, int rounds, boolean parentAlreadyExists) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(HAMMER_THREADS);
		try {
			int failedCalls = 0;
			for (int round = 0; round < rounds; round++) {
				// The real shape is streams/<roomId>/single: the parent exists
				// (the recording chunk lives in it) and only single/ is new.
				// The parent-missing variant widens the race window further.
				Path parent = base.resolve("room" + round);
				if (parentAlreadyExists) {
					Files.createDirectories(parent);
				}
				File dir = parent.resolve("single").toFile();
				assertFalse(dir.exists(), "precondition: directory must not exist before the race");

				CyclicBarrier barrier = new CyclicBarrier(HAMMER_THREADS);
				List<Future<Boolean>> results = new ArrayList<>(HAMMER_THREADS);
				for (int t = 0; t < HAMMER_THREADS; t++) {
					results.add(pool.submit(() -> {
						barrier.await(30, TimeUnit.SECONDS);
						return SingleStreamConversionSubmitter.ensureOutputDir(dir);
					}));
				}
				for (Future<Boolean> result : results) {
					if (!result.get(60, TimeUnit.SECONDS)) {
						failedCalls++;
					}
				}
				assertTrue(dir.isDirectory(), "directory must exist after the race: " + dir);
			}
			return failedCalls;
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void concurrentCreationOfFreshDirWithExistingParentNeverFails(@TempDir Path tmp) throws Exception {
		assertEquals(0, hammer(tmp, HAMMER_ROUNDS, true)
				, "ensureOutputDir() must never report failure just because a concurrent caller created the directory first");
	}

	@Test
	void concurrentCreationOfFreshNestedDirNeverFails(@TempDir Path tmp) throws Exception {
		assertEquals(0, hammer(tmp, HAMMER_ROUNDS, false)
				, "ensureOutputDir() must never report failure just because a concurrent caller created the directory (or its parent) first");
	}

	@Test
	void alreadyExistingDirectoryIsIdempotent(@TempDir Path tmp) throws Exception {
		File dir = tmp.resolve("streams").resolve("42").resolve("single").toFile();
		assertTrue(SingleStreamConversionSubmitter.ensureOutputDir(dir), "first call creates it");
		assertTrue(dir.isDirectory());
		Files.writeString(dir.toPath().resolve("marker.txt"), "keep me", StandardCharsets.UTF_8);

		assertTrue(SingleStreamConversionSubmitter.ensureOutputDir(dir), "second call on an existing directory must succeed");
		assertTrue(SingleStreamConversionSubmitter.ensureOutputDir(dir), "and so must a third");
		assertEquals("keep me", Files.readString(dir.toPath().resolve("marker.txt"), StandardCharsets.UTF_8)
				, "an existing directory's contents must be left alone");
	}

	@Test
	void pathExistingAsRegularFileIsReportedAsFailure(@TempDir Path tmp) throws Exception {
		// The genuine-failure branch: the success of createDirectories() is
		// not "the path exists", it is "a DIRECTORY exists there".
		File asFile = tmp.resolve("single").toFile();
		Files.writeString(asFile.toPath(), "i am a file", StandardCharsets.UTF_8);
		assertFalse(SingleStreamConversionSubmitter.ensureOutputDir(asFile)
				, "a regular file sitting at the output path must be reported as failure");
		assertTrue(asFile.isFile(), "the file must not have been clobbered");

		// Same when a would-be PARENT is a regular file.
		File underFile = new File(asFile, "nested");
		assertFalse(SingleStreamConversionSubmitter.ensureOutputDir(underFile)
				, "a path beneath a regular file can never become a directory");
		assertFalse(underFile.exists());
	}

	@Test
	void convertReportsAnUncreatableOutputDirAsAnErrorEventAndNull(@TempDir Path tmp) throws Exception {
		// Positive control for the causal assertions below: when the output
		// directory GENUINELY cannot be created, convert() must return null AND
		// log exactly the ERROR event the concurrency test looks for -- which is
		// what proves that "zero such events" there is a real statement.
		SingleStreamConversionSubmitter submitter = newSubmitterWithFakeFfmpeg(tmp);
		Long roomId = 777L;
		File chunk = OmFileHelper.getRecordingChunk(roomId, "single_teacher");
		Files.writeString(chunk.toPath(), "raw kurento chunk", StandardCharsets.UTF_8);
		// A regular file squatting exactly where single/ has to go.
		Path squatter = OmFileHelper.getStreamsSubDir(roomId).toPath().resolve("single");
		Files.writeString(squatter, "i am a file, not a directory", StandardCharsets.UTF_8);

		assertNull(submitter.convert(roomId, "teacher")
				, "an output dir that cannot be created must make convert() return null");

		List<ILoggingEvent> conv1 = eventsAt(Level.ERROR, CONV1_MESSAGE);
		assertEquals(1, conv1.size(), "exactly one CONV-1 ERROR event expected, all errors: " + appender.events);
		assertTrue(conv1.get(0).getFormattedMessage().contains(squatter.toString())
				, "the event must name the directory that could not be created: " + conv1.get(0).getFormattedMessage());
		assertTrue(otherErrors().isEmpty(), "no other error expected, got " + otherErrors());
		assertTrue(squatter.toFile().isFile(), "the squatting file must not have been clobbered");
	}

	@Test
	void convertSurvivesConcurrentStopsOfTheSameRoom(@TempDir Path tmp) throws Exception {
		SingleStreamConversionSubmitter submitter = newSubmitterWithFakeFfmpeg(tmp);

		int totalCalls = CONVERT_ROUNDS * CONVERT_ROOMS_PER_ROUND * 2;
		ExecutorService pool = Executors.newFixedThreadPool(CONVERT_ROOMS_PER_ROUND * 2);
		int nullReturns = 0;
		try {
			for (int round = 0; round < CONVERT_ROUNDS; round++) {
				// One barrier per round, spanning EVERY thread of EVERY room, so all
				// the same-room pairs arrive at the output-dir creation together
				// (after convert()'s own identical 250ms stability sleep).
				CyclicBarrier barrier = new CyclicBarrier(CONVERT_ROOMS_PER_ROUND * 2);
				List<Future<File>> results = new ArrayList<>();
				List<Long> rooms = new ArrayList<>();
				for (int r = 0; r < CONVERT_ROOMS_PER_ROUND; r++) {
					Long roomId = 1000L + round * CONVERT_ROOMS_PER_ROUND + r;
					for (String requestId : List.of("teacher", "student")) {
						// getRecordingChunk() also creates streams/<room> itself, so
						// the parent exists and only single/ is new -- as in production.
						File chunk = OmFileHelper.getRecordingChunk(roomId, "single_" + requestId);
						Files.writeString(chunk.toPath(), "raw kurento chunk for " + requestId, StandardCharsets.UTF_8);
						results.add(pool.submit(() -> {
							barrier.await(60, TimeUnit.SECONDS);
							return submitter.convert(roomId, requestId);
						}));
						rooms.add(roomId);
					}
				}
				for (int i = 0; i < results.size(); i++) {
					File mp4 = results.get(i).get(120, TimeUnit.SECONDS);
					if (mp4 == null) {
						nullReturns++;
						continue;
					}
					assertTrue(mp4.isFile(), "reported success but no mp4 at " + mp4);
					assertEquals("converted\n", Files.readString(mp4.toPath(), StandardCharsets.UTF_8)
							, "the mp4 must be the fake ffmpeg's real output for room " + rooms.get(i));
				}
			}
		} finally {
			pool.shutdownNow();
		}

		// 1. The CAUSAL check: the output-dir creation must never have failed.
		List<ILoggingEvent> conv1 = eventsAt(Level.ERROR, CONV1_MESSAGE);
		assertEquals(0, conv1.size(), () -> "CONV-1 REGRESSION: " + conv1.size() + " of " + totalCalls
				+ " concurrent same-room convert() calls failed to create the single/ output directory -- "
				+ "the winner's directory creation made the loser's report failure. First event: "
				+ (conv1.isEmpty() ? "" : conv1.get(0).getFormattedMessage()));

		// 2. Any null that is NOT CONV-1 is something else entirely (a process or
		//    thread cap, an exec failure, ...) -- reported as such, with the logs.
		int others = nullReturns - conv1.size();
		assertEquals(0, others, () -> others + " of " + totalCalls + " convert() calls returned null for a reason "
				+ "OTHER than CONV-1 (no '" + CONV1_MESSAGE + "' event was logged), i.e. most likely an "
				+ "environment limit rather than a race in ensureOutputDir(). Logged ERROR events: " + otherErrors());

		// 3. Non-vacuity: the appender really did capture this run -- one
		//    "conversion done" event per call -- so "zero CONV-1 events" above
		//    is a statement about a log that was being recorded.
		assertEquals(totalCalls, eventsAt(Level.INFO, DONE_MESSAGE).size()
				, "the log capture is not seeing convert()'s own 'conversion done' events, so the CONV-1 check above proves nothing");
	}
}
