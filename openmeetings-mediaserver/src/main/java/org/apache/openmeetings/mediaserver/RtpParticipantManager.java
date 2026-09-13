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
import static java.util.concurrent.CompletableFuture.delayedExecutor;
import static org.apache.openmeetings.db.util.ApplicationHelper.ensureApplication;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.apache.openmeetings.db.dao.user.UserDao;
import org.apache.openmeetings.db.dto.room.RtpParticipantJoinResult;
import org.apache.openmeetings.db.entity.basic.Client;
import org.apache.openmeetings.db.entity.basic.StreamDesc;
import org.apache.openmeetings.db.entity.room.Room;
import org.apache.openmeetings.db.entity.user.User;
import org.apache.openmeetings.db.manager.IClientManager;
import org.apache.openmeetings.db.manager.IRtpParticipantManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/**
 * Adds and manages browser-less RTP participants (the AI teacher stand-in) --
 * see {@link IRtpParticipantManager}.
 *
 * <p>Placed in the same package as {@link StreamProcessor}/{@link KStream}/
 * {@link KRoom} deliberately, so it can call their package-private accessors
 * ({@code StreamProcessor.getByUid}/{@code hasStream}, {@code KurentoHandler.getRoom})
 * without widening any existing visibility -- the same interface-in-db,
 * impl-in-mediaserver split {@link SingleStreamRecordingManager} documents.
 *
 * <p>Because an RTP participant has no browser and therefore no WebSocket, none
 * of OM's normal client-disconnect machinery ever fires for it. A per-room
 * watchdog (modelled on {@code TimerService.doSipCheck}) is the safety net:
 * without it, a session whose media source dies -- or whose external worker
 * crashes without calling {@link #end} -- would leak a roster entry (a ghost
 * tile, and no ROOM_LEAVE row), and could hold a room open indefinitely.
 */
@Singleton
@Named
public class RtpParticipantManager implements IRtpParticipantManager {
	private static final Logger log = LoggerFactory.getLogger(RtpParticipantManager.class);

	// How often the per-room watchdog re-checks its sessions. Same order as
	// TimerService's own sip/mod checks (2-5s); 10s here because the conditions
	// it catches (a dead media source, a runaway session) are not latency
	// sensitive -- a few extra seconds before a ghost tile is reaped is fine.
	private static final int WATCHDOG_INTERVAL_SECONDS = 10;

	// Max-duration backstop when the caller does not supply its own. Set well
	// above any realistic stand-in hold (the Python worker's own hold ceiling
	// is minutes, not tens of minutes) so this only ever fires for a genuinely
	// stuck/abandoned session, never a healthy one. The caller SHOULD pass its
	// own known ceiling (+margin) to join() so this is "just above" in practice;
	// this default is the floor if it passes 0.
	private static final long DEFAULT_MAX_DURATION_MILLIS = TimeUnit.MINUTES.toMillis(35);

	// Bounds how long a join() (a webservice request thread) blocks waiting for
	// Kurento to answer the forward-leg offer. Matches SingleStreamRecordingManager's
	// own 15s stop-confirm bound in spirit -- generous, but not unbounded.
	private static final long JOIN_ANSWER_TIMEOUT_SECONDS = 20;

	@Inject
	private KurentoHandler kHandler;
	@Inject
	private StreamProcessor processor;
	@Inject
	private IClientManager cm;
	@Inject
	private UserDao userDao;

	// sessionId -> live session. ConcurrentHashMap so end()'s atomic remove()
	// is the single point that decides "who actually ends this", making end()
	// idempotent and race-free against the watchdog (see end()).
	private final Map<String, Session> sessions = new ConcurrentHashMap<>();
	// roomId -> the scheduled next watchdog tick for that room. One watchdog per
	// room regardless of how many stand-ins it holds (there is normally one).
	private final Map<Long, CompletableFuture<Object>> watchdogByRoom = new ConcurrentHashMap<>();

	/** In-JVM record of one live RTP-participant session. Single-node only, the
	 * same caveat {@link SingleStreamRecordingManager} already documents. */
	private static final class Session {
		private final String sessionId;
		private final Long roomId;
		private final String clientUid;
		private final String streamUid;
		private final Long userId;
		private final long startMillis;
		private final long maxDurationMillis;
		// Set true once the watchdog has seen a real (human) participant in the
		// room, so condition (c) -- "room went empty of real participants" --
		// only fires after one was actually present, never during the legitimate
		// pre-launch window where the stand-in is deliberately alone waiting.
		private volatile boolean realSeen;

		Session(String sessionId, Long roomId, String clientUid, String streamUid, Long userId, long startMillis, long maxDurationMillis) {
			this.sessionId = sessionId;
			this.roomId = roomId;
			this.clientUid = clientUid;
			this.streamUid = streamUid;
			this.userId = userId;
			this.startMillis = startMillis;
			this.maxDurationMillis = maxDurationMillis;
		}
	}

	@Override
	public RtpParticipantJoinResult join(Long roomId, String externalId, String externalType, boolean videoEnabled, int width, int height, String sdpOffer, String reverseSdpOffer, long maxDurationSeconds) {
		// Bind the Wicket Application to this (webservice request) thread --
		// cm.add/addToRoom/update all call Application.get(). performCall's
		// session check binds it for the normal case, but bind explicitly here
		// too so this manager never depends on the caller having done so (matches
		// SingleStreamRecordingManager's own defensive ensureApplication).
		ensureApplication();
		if (!kHandler.isConnected()) {
			throw new IllegalStateException("Media server is not connected");
		}
		KRoom kRoom = kHandler.getRoom(roomId);
		if (kRoom == null) {
			throw new IllegalStateException("No room " + roomId);
		}
		// REAL identity, fail-closed. This is the record every human participant
		// is matched against, so every conference_log row this session emits is
		// correctly person-scoped to a genuine user id -- which the Moodle
		// plugin's attendance/pay logic depends on. Never invent a user here.
		User u = userDao.getExternalUser(externalId, externalType);
		if (u == null) {
			throw new IllegalStateException("No OM user for externalId=" + externalId + ", externalType=" + externalType
					+ " -- provision the stand-in's OM user before joining");
		}

		Client c = new Client("rtp-" + randomUUID(), 0, u, u.getPictureUri());
		c.setRoom(kRoom.getRoom());
		if (videoEnabled) {
			c.allow(Room.Right.AUDIO, Room.Right.VIDEO);
		} else {
			c.allow(Room.Right.AUDIO);
		}
		c.setWidth(width);
		c.setHeight(height);
		cm.add(c);
		cm.addToRoom(c);
		// WebcamStreamDesc's constructor auto-adds AUDIO (and VIDEO if allowed)
		// from the client's rights set above; the toggle marks mic/cam enabled.
		StreamDesc sd = c.addStream(Client.StreamType.WEBCAM, videoEnabled ? Client.Activity.AUDIO_VIDEO : Client.Activity.AUDIO);
		sd.setWidth(width).setHeight(height);
		cm.update(c);

		KStream stream = kRoom.join(sd);
		CompletableFuture<String> answerF = new CompletableFuture<>();
		stream.startRtpParticipant(sd, sdpOffer, answerF::complete);
		String answer;
		try {
			answer = answerF.get(JOIN_ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			cleanupFailedJoin(c, stream);
			throw new IllegalStateException("Interrupted waiting for RTP participant answer", e);
		} catch (ExecutionException | TimeoutException e) {
			cleanupFailedJoin(c, stream);
			throw new IllegalStateException("RTP participant did not produce an answer: " + e.getMessage(), e);
		}
		if (answer == null || answer.startsWith("ERROR:")) {
			cleanupFailedJoin(c, stream);
			throw new IllegalStateException("RTP participant endpoint setup failed: " + answer);
		}

		// Reverse audio leg is best-effort at join time: the forward leg (the
		// stand-in being seen/heard) has already succeeded, and a real
		// participant that is not present yet can still be wired later
		// (onRealParticipantJoined). A failure here must not fail the whole join.
		String reverseAnswer = null;
		if (reverseSdpOffer != null && !reverseSdpOffer.isBlank()) {
			try {
				reverseAnswer = kRoom.activateAiStandinReverseLeg(reverseSdpOffer);
			} catch (RuntimeException e) {
				log.warn("RTP participant: reverse leg activation failed for room {} -- continuing without it", roomId, e);
			}
		}

		String sessionId = randomUUID().toString();
		long maxMillis = maxDurationSeconds > 0 ? TimeUnit.SECONDS.toMillis(maxDurationSeconds) : DEFAULT_MAX_DURATION_MILLIS;
		Session session = new Session(sessionId, roomId, c.getUid(), sd.getUid(), u.getId(), System.currentTimeMillis(), maxMillis);
		sessions.put(sessionId, session);
		ensureWatchdog(roomId);
		log.info("RTP participant joined: room {}, sessionId {}, userId {} (extId {}), clientUid {}, streamUid {}, video {}, reverseLeg {}, maxDurationMs {}",
				roomId, sessionId, u.getId(), externalId, c.getUid(), sd.getUid(), videoEnabled,
				reverseSdpOffer != null && !reverseSdpOffer.isBlank(), maxMillis);
		return new RtpParticipantJoinResult(sessionId, answer, reverseAnswer, c.getUid(), sd.getUid());
	}

	@Override
	public boolean end(String sessionId) {
		// The single, atomic decision point: whoever removes the session from
		// the map is the one caller that actually ends it. A concurrent
		// caller (an explicit end() racing a watchdog reap, or two end()s)
		// gets null here and returns false quietly -- no throw, no second
		// ROOM_LEAVE. This is what makes end() idempotent.
		Session s = sessions.remove(sessionId);
		if (s == null) {
			return false;
		}
		ensureApplication();
		log.info("RTP participant ending: sessionId {}, room {}, clientUid {}, streamUid {}", sessionId, s.roomId, s.clientUid, s.streamUid);
		KRoom kRoom = kHandler.getRoom(s.roomId);
		if (kRoom != null) {
			try {
				kRoom.deactivateAiStandinReverseLeg();
			} catch (RuntimeException e) {
				log.warn("RTP participant end: reverse leg deactivation failed, session {}", sessionId, e);
			}
		}
		// Release the stand-in's own media stream, if it is still present (the
		// watchdog's condition (a) reaps sessions whose stream is already gone,
		// in which case this is simply a no-op).
		KStream stream = processor.getByUid(s.streamUid);
		if (stream != null) {
			try {
				stream.release(true);
			} catch (RuntimeException e) {
				log.warn("RTP participant end: stream release failed, session {}", sessionId, e);
			}
		}
		// Remove from the room roster -> emits ROOM_LEAVE + CLIENT_DISCONNECT
		// conference_log rows, person-scoped to the stand-in's real user id.
		Client c = cm.get(s.clientUid);
		if (c != null) {
			try {
				cm.exit(c);
			} catch (RuntimeException e) {
				log.warn("RTP participant end: client exit failed, session {}", sessionId, e);
			}
		} else {
			log.info("RTP participant end: client {} already gone from roster, session {}", s.clientUid, sessionId);
		}
		return true;
	}

	/**
	 * Best-effort teardown of a join that failed after the client was already
	 * added to the room but before a session was registered -- so a failed join
	 * never leaks a roster entry.
	 */
	private void cleanupFailedJoin(Client c, KStream stream) {
		try {
			if (stream != null) {
				stream.release(true);
			}
		} catch (RuntimeException e) {
			log.warn("RTP participant: cleanup of failed join -- stream release failed", e);
		}
		try {
			cm.exit(c);
		} catch (RuntimeException e) {
			log.warn("RTP participant: cleanup of failed join -- client exit failed", e);
		}
	}

	private synchronized void ensureWatchdog(Long roomId) {
		if (!watchdogByRoom.containsKey(roomId)) {
			scheduleWatchdog(roomId);
		}
	}

	private void scheduleWatchdog(Long roomId) {
		watchdogByRoom.put(roomId, new CompletableFuture<>().completeAsync(() -> {
			runWatchdogTick(roomId);
			return null;
		}, delayedExecutor(WATCHDOG_INTERVAL_SECONDS, TimeUnit.SECONDS)));
	}

	private void runWatchdogTick(Long roomId) {
		try {
			ensureApplication();
			List<Session> roomSessions = sessions.values().stream()
					.filter(sess -> roomId.equals(sess.roomId))
					.toList();
			if (roomSessions.isEmpty()) {
				watchdogByRoom.remove(roomId);
				return;
			}
			final long now = System.currentTimeMillis();
			final boolean realPresent = isRealParticipantPresent(roomId, roomSessions);
			for (Session s : roomSessions) {
				// (a) The media source died: Kurento's own flow-out timeout
				// released the KStream, but nothing removed the roster entry
				// (no WebSocket to notice). Reap the ghost.
				boolean streamGone = !processor.hasStream(s.streamUid);
				boolean rosterPresent = cm.get(s.clientUid) != null;
				if (streamGone && rosterPresent) {
					log.warn("RTP participant watchdog: stream {} gone but roster leaked -- reaping session {} (room {})", s.streamUid, s.sessionId, roomId);
					end(s.sessionId);
					continue;
				}
				// (b) Max-duration backstop: a runaway/abandoned session.
				if (now - s.startMillis > s.maxDurationMillis) {
					log.warn("RTP participant watchdog: session {} exceeded max duration {}ms -- reaping (room {})", s.sessionId, s.maxDurationMillis, roomId);
					end(s.sessionId);
					continue;
				}
				// (c) Room went empty of real participants AFTER one had been
				// present -- the class is genuinely over; the stand-in should not
				// hold an empty room. Never fires during the pre-launch window
				// (realSeen guards it), where being alone is expected.
				if (realPresent) {
					s.realSeen = true;
				} else if (s.realSeen) {
					log.warn("RTP participant watchdog: room {} emptied of real participants -- reaping session {}", roomId, s.sessionId);
					end(s.sessionId);
					continue;
				}
			}
		} catch (RuntimeException e) {
			log.error("RTP participant watchdog error, room {}", roomId, e);
		} finally {
			// Reschedule while any session for this room remains; otherwise let
			// the watchdog lapse. Kept in finally so a transient error in a tick
			// never permanently disables the watchdog.
			if (sessions.values().stream().anyMatch(sess -> roomId.equals(sess.roomId))) {
				scheduleWatchdog(roomId);
			} else {
				watchdogByRoom.remove(roomId);
			}
		}
	}

	private boolean isRealParticipantPresent(Long roomId, List<Session> roomSessions) {
		Set<Long> botUserIds = roomSessions.stream()
				.map(sess -> sess.userId)
				.collect(Collectors.toSet());
		return cm.streamByRoom(roomId)
				.anyMatch(client -> client.getUserId() != null && !botUserIds.contains(client.getUserId()));
	}
}
