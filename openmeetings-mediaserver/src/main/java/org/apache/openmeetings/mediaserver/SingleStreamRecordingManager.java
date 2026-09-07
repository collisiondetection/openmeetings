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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.openmeetings.db.dto.record.SingleStreamRecordingStart;
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

	// Real, measured worst case for Kurento's own stopAndWait confirmation to
	// land is well under this (observed up to ~4s during the investigation
	// that scoped this class's own stop-race fix) -- this leaves genuine
	// margin above that while still bounding how long a caller of
	// stopSingle() (a webservice request thread) can be blocked if the
	// media server never confirms at all, e.g. a lost connection.
	private static final long STOP_CONFIRM_TIMEOUT_SECONDS = 15;

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

	// requestId -> the CompletableFuture tracking that requestId's own
	// in-progress stop -- present ONLY while a "claimant" (see stopSingle())
	// is actively finalizing it, removed the moment that claimant returns,
	// success or failure alike. Exists so a second, concurrent stopSingle()
	// call for the SAME requestId can wait on the SAME real Kurento outcome
	// instead of separately losing the streamUidByRequestId.remove() race
	// above (which only one caller can ever win) and reporting success for
	// having merely lost that race -- before the winner's own stop is even
	// confirmed. Without this, a "losing" concurrent caller could tell ITS
	// OWN caller (the webservice, which proceeds straight to conversion on a
	// `true` result) that it was safe to convert while the winner's real
	// Kurento confirmation might still be pending -- the same still-open-file
	// hazard this class's whole convert-after-confirmed-stop design already
	// exists to prevent for the single-caller case, just reachable again via
	// a second caller instead. In-JVM only, same caveat as the map above.
	private final Map<String, CompletableFuture<Boolean>> inFlightStops = new ConcurrentHashMap<>();

	@Override
	public SingleStreamRecordingStart startSingle(Long roomId, String externalUserId) {
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
		// startSingleRecord() sets this synchronously, strictly before it
		// returns true, and KStream never clears it back to null afterwards
		// (see that field's own javadoc for why) -- null here should be
		// unreachable, but fall back rather than propagate an NPE up into the
		// webservice layer if some future refactor ever breaks that invariant.
		Long startTime = stream.getSingleRecordStartTime();
		if (startTime == null) {
			log.warn("Single-stream recording started but no start time was recorded, room {}, requestId {} -- falling back to now", roomId, requestId);
			startTime = System.currentTimeMillis();
		}
		log.info("Started single-stream recording, room {}, externalUserId {}, requestId {}, startTime {}", roomId, externalUserId, requestId, startTime);
		return new SingleStreamRecordingStart(requestId, startTime);
	}

	@Override
	public boolean stopSingle(Long roomId, String requestId) {
		// Claimed at most once per requestId, no matter how many callers
		// arrive concurrently for it: putIfAbsent() atomically inserts
		// myCompletion only if nothing is there yet, and its return value
		// tells every caller whether IT was the one that actually inserted
		// it. A concurrent second (or third, ...) caller for the SAME
		// requestId therefore finds the first caller's own future here and
		// waits on that SAME real outcome below (see awaitStopConfirmation()),
		// rather than separately losing the streamUidByRequestId race further
		// down -- which only the winner can ever win anyway -- and reporting
		// success merely for having lost it. See inFlightStops's own javadoc
		// for why that mattered.
		CompletableFuture<Boolean> myCompletion = new CompletableFuture<>();
		CompletableFuture<Boolean> existing = inFlightStops.putIfAbsent(requestId, myCompletion);
		boolean isClaimant = existing == null;
		CompletableFuture<Boolean> completion = isClaimant ? myCompletion : existing;
		long startNanos = System.nanoTime();
		if (!isClaimant) {
			log.info("stopSingle: a concurrent stop for requestId {} in room {} is already being finalized -- waiting on its real outcome instead of assuming success", requestId, roomId);
			return awaitStopConfirmation(completion, roomId, requestId, startNanos);
		}
		try {
			String streamUid = streamUidByRequestId.remove(requestId);
			if (streamUid == null) {
				log.info("stopSingle: no in-progress recording for requestId {} in room {} -- already stopped or never started", requestId, roomId);
				completion.complete(true);
				return true;
			}
			KStream stream = processor.getByUid(streamUid);
			if (stream == null) {
				// The participant already disconnected, which independently calls
				// KStream.release() -> stopSingleRecord() -- the file is already
				// finalized, there is simply nothing left here to stop.
				log.info("stopSingle: stream {} (requestId {}) is already gone in room {}", streamUid, requestId, roomId);
				completion.complete(true);
				return true;
			}
			// stream.stopSingleRecord()'s own completion callback fires
			// asynchronously -- on a separate Kurento JSON-RPC client thread,
			// once the media server has genuinely finished finalizing the
			// recording, NOT synchronously within the call below (see that
			// method's own javadoc). Block THIS thread -- a webservice request
			// thread, not Kurento's own event-dispatch thread, so this wait can
			// never stall Kurento's event delivery for any other stream -- on a
			// CompletableFuture until that real completion lands, so THIS
			// method's own caller (the webservice, deciding whether it is safe
			// to run conversion) gets back an answer that reflects whether the
			// file is genuinely finalized, not merely whether the stop command
			// was issued.
			try {
				stream.stopSingleRecord(completion::complete);
			} catch (RuntimeException e) {
				// stopSingleRecord() declares no checked exceptions, so the
				// only thing that can escape this call is an unchecked
				// RuntimeException from the Kurento client call it makes
				// internally (RecorderEndpoint.stopAndWait()) -- possible
				// synchronously, e.g. if the Kurento client connection drops
				// in the exact instant between KStream's own
				// singleRecorder-null check and dispatching the stop. Caught
				// here rather than left to propagate past this method
				// (which performCall's own catch-all would otherwise turn
				// into a raw thrown ServiceException, i.e. an HTTP 500,
				// instead of the descriptive `false` -> Type.ERROR result
				// every other failure mode here produces) -- and completed
				// as `false` so a concurrently-waiting caller above doesn't
				// have to sit out the full STOP_CONFIRM_TIMEOUT_SECONDS just
				// to find out. Not a double-completion risk: if
				// stopAndWait() throws before ever registering its
				// Continuation with Kurento, that Continuation can never
				// fire, so completing it here is the only way it ever will
				// be.
				log.warn("stopSingle: stream.stopSingleRecord() threw synchronously, room {}, requestId {} -- treating as unsafe to convert", roomId, requestId, e);
				completion.complete(false);
				return false;
			}
			return awaitStopConfirmation(completion, roomId, requestId, startNanos);
		} finally {
			inFlightStops.remove(requestId, completion);
		}
	}

	/**
	 * Waits for {@code completion} to be resolved by whichever caller is
	 * actually responsible for finishing this requestId's stop (the
	 * {@code isClaimant} split in {@link #stopSingle}), and translates every
	 * way that wait can fail into the same conservative {@code false} ("not
	 * safely confirmed, do not convert") this class already reports for
	 * every other failure mode. Shared by both the claimant (which dispatches
	 * the real stop just before calling this) and any concurrent caller that
	 * only ever waits.
	 */
	private boolean awaitStopConfirmation(CompletableFuture<Boolean> completion, Long roomId, String requestId, long startNanos) {
		try {
			boolean ok = completion.get(STOP_CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			log.info("Stopped single-stream recording, room {}, requestId {}, confirmed {}, took {}ms", roomId, requestId, ok, (System.nanoTime() - startNanos) / 1_000_000);
			return ok;
		} catch (TimeoutException e) {
			log.warn("stopSingle: media server did not confirm the stop within {}s, room {}, requestId {} -- treating as unsafe to convert", STOP_CONFIRM_TIMEOUT_SECONDS, roomId, requestId);
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("stopSingle: interrupted waiting for the media server to confirm the stop, room {}, requestId {}", roomId, requestId, e);
			return false;
		} catch (ExecutionException e) {
			// completion is only ever completed with a plain boolean -- via
			// Consumer<Boolean>::accept (KStream.stopSingleRecord()'s
			// `then`) or this class's own synchronous-throw fallback above --
			// neither of which can raise into the future as a failure; kept
			// only because CompletableFuture.get() declares this checked
			// exception.
			log.warn("stopSingle: unexpected error waiting for the media server to confirm the stop, room {}, requestId {}", roomId, requestId, e);
			return false;
		}
	}
}
