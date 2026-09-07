/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
 */
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
import static org.apache.openmeetings.mediaserver.KurentoHandler.PARAM_CANDIDATE;
import static org.apache.openmeetings.mediaserver.KurentoHandler.PARAM_ICE;
import static org.apache.openmeetings.mediaserver.KurentoHandler.TAG_ROOM;
import static org.apache.openmeetings.mediaserver.KurentoHandler.TAG_STREAM_UID;
import static org.apache.openmeetings.mediaserver.KurentoHandler.getFlowoutTimeout;
import static org.apache.openmeetings.mediaserver.KurentoHandler.newKurentoMsg;
import static org.apache.openmeetings.util.OmFileHelper.getRecUri;
import static org.apache.openmeetings.util.OmFileHelper.getRecordingChunk;

import java.util.Date;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import jakarta.inject.Inject;

import org.apache.openmeetings.core.sip.ISipCallbacks;
import org.apache.openmeetings.core.sip.SipManager;
import org.apache.openmeetings.core.sip.SipStackProcessor;
import org.apache.openmeetings.core.util.WebSocketHelper;
import org.apache.openmeetings.db.dao.record.RecordingChunkDao;
import org.apache.openmeetings.db.entity.basic.Client;
import org.apache.openmeetings.db.entity.basic.Client.Activity;
import org.apache.openmeetings.db.entity.basic.StreamDesc;
import org.apache.openmeetings.db.entity.basic.Client.StreamType;
import org.apache.openmeetings.db.entity.record.RecordingChunk.Type;
import org.apache.openmeetings.db.util.ws.RoomMessage;
import org.apache.openmeetings.db.util.ws.TextRoomMessage;
import org.apache.openmeetings.util.OmFileHelper;
import org.apache.wicket.injection.Injector;
import org.kurento.client.BaseRtpEndpoint;
import org.kurento.client.Continuation;
import org.kurento.client.IceCandidate;
import org.kurento.client.ListenerSubscription;
import org.kurento.client.MediaFlowState;
import org.kurento.client.MediaObject;
import org.kurento.client.MediaPipeline;
import org.kurento.client.MediaProfileSpecType;
import org.kurento.client.MediaType;
import org.kurento.client.RecorderEndpoint;
import org.kurento.client.RtpEndpoint;
import org.kurento.client.WebRtcEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.openjson.JSONObject;

public class KStream extends AbstractStream implements ISipCallbacks {
	private static final Logger log = LoggerFactory.getLogger(KStream.class);

	@Inject
	private KurentoHandler kHandler;
	@Inject
	private StreamProcessor processor;
	@Inject
	private RecordingChunkDao chunkDao;
	@Inject
	private SipManager sipManager;

	private final KRoom kRoom;
	private final Date connectedSince;
	private final StreamType streamType;
	private MediaProfileSpecType profile;
	private MediaPipeline pipeline;
	private RecorderEndpoint recorder;
	private BaseRtpEndpoint outgoingMedia = null;
	private Queue<IceCandidate> candidatesQueue = new ConcurrentLinkedQueue<>();
	private RtpEndpoint rtpEndpoint;
	private Optional<SipStackProcessor> sipProcessor = Optional.empty();
	private final Map<String, WebRtcEndpoint> listeners = new ConcurrentHashMap<>();
	private Optional<CompletableFuture<Object>> flowoutFuture = Optional.empty();
	private ListenerSubscription flowoutSubscription;
	private Long chunkId;
	private Type type;
	private boolean hasAudio;
	private boolean hasVideo;
	private boolean hasScreen;
	private boolean sipClient;
	// Single-participant recording: a second, independent RecorderEndpoint on
	// the same outgoingMedia, deliberately never touching recorder/chunkId/
	// kRoom.getRecordingId() above -- this must work whether or not the room's
	// own Record button has ever been pressed, so it can't share that state.
	private RecorderEndpoint singleRecorder;
	private String singleRecordRequestId;
	// The OM server's own wall-clock instant (epoch millis) the most recently
	// started single-stream recording was activated -- set inside
	// startSingleRecord(), read by SingleStreamRecordingManager immediately
	// after a successful start so it can hand the instant back to the REST
	// caller. Deliberately NOT cleared in stopSingleRecord()/on release: the
	// only reader looks at it right after ITS OWN startSingleRecord() call
	// just returned true, so leaving the previous value in place after a stop
	// is harmless (a genuinely stale value is simply never read), whereas
	// nulling it here would open a narrow race -- a concurrent stopSingleRecord()
	// landing between startSingleRecord() returning and the manager reading
	// this getter -- that would otherwise turn into an NPE at the call site.
	private Long singleRecordStartTime;

	public KStream(final StreamDesc sd, KRoom kRoom) {
		super(sd.getSid(), sd.getUid());
		this.kRoom = kRoom;
		streamType = sd.getType();
		this.connectedSince = new Date();
		Injector.get().inject(this);
	}

	public void startBroadcast(final StreamDesc sd, final String sdpOffer, Runnable then) {
		if (outgoingMedia != null) {
			release(false);
		}
		hasAudio = sd.has(Activity.AUDIO);
		hasVideo = sd.has(Activity.VIDEO);
		hasScreen = sd.has(Activity.SCREEN);
		sipClient = OmFileHelper.SIP_USER_ID.equals(sd.getClient().getUserId());
		if ((sdpOffer.indexOf("m=audio") > -1 && !hasAudio)
				|| (sdpOffer.indexOf("m=video") > -1 && !hasVideo && StreamType.SCREEN != streamType))
		{
			log.warn("Broadcast started without enough rights, sid {}, uid {}", sid, uid);
			return;
		}
		if (StreamType.SCREEN == streamType) {
			type = Type.SCREEN;
		} else {
			if (hasAudio && hasVideo) {
				type = Type.AUDIO_VIDEO;
			} else if (hasVideo) {
				type = Type.VIDEO_ONLY;
			} else {
				type = Type.AUDIO_ONLY;
			}
		}
		switch (type) {
			case AUDIO_VIDEO:
				profile = MediaProfileSpecType.WEBM;
				break;
			case AUDIO_ONLY:
				profile = MediaProfileSpecType.WEBM_AUDIO_ONLY;
				break;
			case SCREEN, VIDEO_ONLY:
			default:
				profile = MediaProfileSpecType.WEBM_VIDEO_ONLY;
				break;
		}
		pipeline = kHandler.createPipiline(Map.of(TAG_ROOM, String.valueOf(getRoomId()), TAG_STREAM_UID, sd.getUid()), new Continuation<Void>() {
			@Override
			public void onSuccess(Void result) throws Exception {
				if (sipClient) {
					addSipProcessor(1);
				} else {
					outgoingMedia = createEndpoint(sd.getSid(), sd.getUid(), true);
					internalStartBroadcast(sd, sdpOffer);
					notifyOnNewStream(sd);
				}
				then.run();
			}

			@Override
			public void onError(Throwable cause) throws Exception {
				log.warn("Unable to create pipeline {}", KStream.this.uid, cause);
			}
		});
	}

	/**
	 * Invoked in case stream stops to decide on if this stream is worth stopping.
	 *
	 * Stop broadcast in case:
	 *  - If mediaType is anything other then MediaType.AUDIO
	 *  - If type Audio stop in case it has no Video attached
	 *
	 * @param mediaType the MediaType that stopped flowing
	 * @return true in case this stream should be dropped
	 */
	protected boolean checkFlowOutEventForStopping(MediaType mediaType) {
		return MediaType.AUDIO != mediaType || !hasVideo;
	}

	private void internalStartBroadcast(final StreamDesc sd, final String sdpOffer) {
		outgoingMedia.addMediaSessionTerminatedListener(evt -> log.warn("Media stream terminated {}", sd));
		flowoutSubscription = outgoingMedia.addMediaFlowOutStateChangedListener(evt -> {
			log.info("Media Flow OUT STATE :: {}, mediaType {}, source {}, sid {}, uid {}"
					, evt.getState(), evt.getMediaType(), evt.getSource(), sid, uid);
			if (MediaFlowState.NOT_FLOWING == evt.getState()
					&& checkFlowOutEventForStopping(evt.getMediaType())) {
				log.warn("FlowOut Future is created, sid {}, uid {}", sid, uid);
				flowoutFuture = Optional.of(new CompletableFuture<>().completeAsync(() -> {
					log.warn("KStream will be dropped {}, sid {}, uid {}", sd, sid, uid);
					if (StreamType.SCREEN == streamType) {
						processor.doStopSharing(sid, uid);
					}
					stopBroadcast();
					return null;
				}, delayedExecutor(getFlowoutTimeout(), TimeUnit.SECONDS)));
			} else {
				dropFlowoutFuture();
			}
		});
		outgoingMedia.addMediaFlowInStateChangedListener(evt -> log.warn("Media Flow IN :: {}, {}, {}, sid {}, uid {}"
				, evt.getState(), evt.getMediaType(), evt.getSource(), sid, uid));
		if (!sipClient) {
			addListener(sd.getSid(), sd.getUid(), sdpOffer);
			addSipProcessor(kRoom.getSipCount());
		}
		if (kRoom.isRecording()) {
			startRecord();
		}
	}

	private void notifyOnNewStream(final StreamDesc sd) {
		Client c = sd.getClient();
		WebSocketHelper.sendRoom(new TextRoomMessage(c.getRoomId(), c, RoomMessage.Type.RIGHT_UPDATED, c.getUid()));
		if (hasAudio || hasVideo || hasScreen) {
			WebSocketHelper.sendRoomOthers(getRoomId(), c.getUid(), newKurentoMsg()
					.put("id", "newStream")
					.put(PARAM_ICE, kHandler.getTurnServers(c))
					.put("stream", sd.toJson()));
		}
	}

	public void broadcastRestarted() {
		if (outgoingMedia != null && flowoutSubscription != null) {
			outgoingMedia.removeMediaFlowOutStateChangedListener(flowoutSubscription);
		}
		dropFlowoutFuture();
	}

	private void dropFlowoutFuture() {
		flowoutFuture.ifPresent(f -> {
			log.warn("FlowOut Future is canceled");
			f.cancel(true);
			flowoutFuture = Optional.empty();
		});
	}

	public void addListener(String sid, String uid, String sdpOffer) {
		final boolean self = uid.equals(this.uid);
		log.info("USER: have started, sid {}, uid {}, mode {} in kRoom {}", sid, uid, self ? "broadcasting" : "receiving", getRoomId());
		log.trace("USER {}: SdpOffer is {}", uid, sdpOffer);
		if (!self && outgoingMedia == null) {
			log.warn("Trying to add listener too early, sid {}, uid {}", sid, uid);
			return;
		}

		final BaseRtpEndpoint endpoint = getEndpointForUser(sid, uid);
		final String sdpAnswer = endpoint.processOffer(sdpOffer);

		if (endpoint instanceof WebRtcEndpoint rtcEndpoint) {
			log.debug("gather candidates, sid {}, uid {}", sid, uid);
			rtcEndpoint.gatherCandidates(); // this one might throw Exception
		}
		log.trace("USER {}: SdpAnswer is {}", this.uid, sdpAnswer);
		kHandler.sendClient(sid, newKurentoMsg()
				.put("id", "videoResponse")
				.put("uid", this.uid)
				.put("sdpAnswer", sdpAnswer));
	}

	private BaseRtpEndpoint getEndpointForUser(String sid, String uid) {
		if (uid.equals(this.uid)) {
			log.debug("PARTICIPANT {}: configuring loopback", this.uid);
			return outgoingMedia;
		}

		log.debug("PARTICIPANT {}: receiving video from {}", uid, this.uid);
		WebRtcEndpoint listener = listeners.remove(uid);
		if (listener != null) {
			log.debug("PARTICIPANT {}: re-started video receiving, will drop previous endpoint", uid);
			listener.release();
		}
		log.debug("PARTICIPANT {}: creating new endpoint for {}", uid, this.uid);
		listener = createEndpoint(sid, uid, false);
		listeners.put(uid, listener);

		log.debug("PARTICIPANT {}: obtained endpoint for {}", uid, this.uid);
		Client cur = processor.getBySid(this.sid);
		if (cur == null) {
			log.warn("Client for endpoint dooesn't exists");
		} else {
			StreamDesc sd = cur.getStream(this.uid);
			if (sd == null) {
				log.warn("Stream for endpoint dooesn't exists");
			} else {
				if (sd.has(Activity.AUDIO)) {
					outgoingMedia.connect(listener, MediaType.AUDIO);
				}
				if (StreamType.SCREEN == streamType || sd.has(Activity.VIDEO)) {
					outgoingMedia.connect(listener, MediaType.VIDEO);
				}
			}
		}
		return listener;
	}

	private void setTags(MediaObject endpoint, String uid) {
		endpoint.addTag("outUid", this.uid);
		endpoint.addTag("uid", uid);
	}

	private RtpEndpoint getRtpEndpoint(MediaPipeline pipeline) {
		RtpEndpoint endpoint = new RtpEndpoint.Builder(pipeline).build();
		setTags(endpoint, uid);
		return endpoint;
	}

	private WebRtcEndpoint createEndpoint(String sid, String uid, boolean recv) {
		WebRtcEndpoint endpoint = createWebRtcEndpoint(pipeline, recv, kHandler.getCertificateType());
		setTags(endpoint, uid);
		reApplyIceCandiates(endpoint, recv);

		endpoint.addIceCandidateFoundListener(evt -> kHandler.sendClient(sid
				, newKurentoMsg()
					.put("id", "iceCandidate")
					.put("uid", KStream.this.uid)
					.put(PARAM_CANDIDATE, new JSONObject(evt.getCandidate())))
				);
		return endpoint;
	}

	private void reApplyIceCandiates(WebRtcEndpoint endpoint, boolean recv) {
		// sender candidates
		if (recv && !candidatesQueue.isEmpty()) {
				log.trace("addIceCandidate iceCandidate reply from not ready, uid: {}", uid);
				candidatesQueue.forEach(endpoint::addIceCandidate);
				candidatesQueue.clear();
		}
	}

	public void startRecord() {
		log.debug("startRecord outMedia OK ? {}", outgoingMedia != null);
		if (outgoingMedia == null) {
			release(true);
			return;
		}
		final String chunkUid = "rec_" + kRoom.getRecordingId() + "_" + randomUUID();
		recorder = createRecorderEndpoint(pipeline, getRecUri(getRecordingChunk(getRoomId(), chunkUid)), profile);
		setTags(recorder, uid);

		recorder.addRecordingListener(evt -> chunkId = chunkDao.start(kRoom.getRecordingId(), type, chunkUid, sid));
		recorder.addStoppedListener(evt -> {
			chunkDao.stop(chunkId);
			chunkId = null;
		});
		switch (profile) {
			case WEBM:
				outgoingMedia.connect(recorder, MediaType.AUDIO);
				outgoingMedia.connect(recorder, MediaType.VIDEO);
				break;
			case WEBM_VIDEO_ONLY:
				outgoingMedia.connect(recorder, MediaType.VIDEO);
				break;
			case WEBM_AUDIO_ONLY:
			default:
				outgoingMedia.connect(recorder, MediaType.AUDIO);
				break;
		}
		recorder.record(new Continuation<Void>() {
			@Override
			public void onSuccess(Void result) throws Exception {
				log.info("Recording started successfully");
			}

			@Override
			public void onError(Throwable cause) throws Exception {
				log.error("Failed to start recording", cause);
			}
		});
	}

	public void stopRecord() {
		stopRecorder(true, () -> {});
	}

	/**
	 * Records ONLY this stream, on a second, independent RecorderEndpoint --
	 * deliberately never touches {@code recorder}/{@code chunkId}/
	 * {@code chunkDao}, so it works whether or not the room's own Record
	 * button has ever been pressed, and can never collide with a concurrent
	 * whole-room recording of the same stream. Not registered with OM's
	 * normal recording pipeline at all; the caller is responsible for
	 * locating and converting the resulting chunk.
	 *
	 * @param requestId caller-supplied id, embedded in the chunk filename so
	 *                  the caller can locate it without any lookup back into
	 *                  this class
	 * @return false if refused outright (no media, no video, or a
	 *         single-stream recording is already in progress on this
	 *         stream) -- the caller should treat this the same as any other
	 *         "could not start" case and not retry immediately
	 */
	public synchronized boolean startSingleRecord(String requestId) {
		log.debug("startSingleRecord outMedia OK ? {}, hasVideo ? {}", outgoingMedia != null, hasVideo);
		if (outgoingMedia == null || singleRecorder != null || !hasVideo) {
			return false;
		}
		final String chunkUid = "single_" + requestId;
		singleRecorder = createRecorderEndpoint(pipeline, getRecUri(getRecordingChunk(getRoomId(), chunkUid)), profile);
		setTags(singleRecorder, uid);
		switch (profile) {
			case WEBM:
				outgoingMedia.connect(singleRecorder, MediaType.AUDIO);
				outgoingMedia.connect(singleRecorder, MediaType.VIDEO);
				break;
			case WEBM_VIDEO_ONLY:
				outgoingMedia.connect(singleRecorder, MediaType.VIDEO);
				break;
			case WEBM_AUDIO_ONLY:
			default:
				outgoingMedia.connect(singleRecorder, MediaType.AUDIO);
				break;
		}
		singleRecordRequestId = requestId;
		// Captured here, synchronously, rather than in record()'s own
		// Continuation.onSuccess below (which fires async, well after this
		// method must already have returned to a caller that has to answer
		// an HTTP request in the same call) or via a Kurento
		// addRecordingListener callback (the pattern startRecord() uses for
		// the whole-room case's RecordingChunk.start -- also async, and only
		// fine there because nothing is waiting on it). This is therefore
		// the OM server's own instant it ISSUED the activation command, not
		// Kurento's later confirmation that media is genuinely flowing --
		// close enough for the caller's actual purpose (correlating against
		// the whiteboard recording log's own System.currentTimeMillis() "ts"
		// values, see WbRecordingManager) without turning this method into a
		// blocking call.
		singleRecordStartTime = System.currentTimeMillis();
		singleRecorder.record(new Continuation<Void>() {
			@Override
			public void onSuccess(Void result) throws Exception {
				log.info("Single-stream recording started successfully, uid {}, requestId {}", KStream.this.uid, requestId);
			}

			@Override
			public void onError(Throwable cause) throws Exception {
				log.error("Failed to start single-stream recording, uid {}, requestId {}", KStream.this.uid, requestId, cause);
			}
		});
		return true;
	}

	/**
	 * Stops and finalizes the single-stream recording started by
	 * {@link #startSingleRecord(String)}, if any -- a no-op otherwise (e.g.
	 * the participant already disconnected, which independently calls this
	 * via {@link #release(boolean)} below).
	 *
	 * <p>Despite the name, {@code stopAndWait} does NOT block the calling
	 * thread -- verified live (2026-09-07): its {@link Continuation} fires
	 * asynchronously, on a separate Kurento JSON-RPC client thread (observed
	 * as {@code ventExec-e2-tNN} against a caller thread named
	 * {@code nio-5443-execN}), and can arrive several seconds after this
	 * method has already returned -- Kurento's own server-side EOS/mux
	 * finalization is what {@code stopAndWait} actually waits for, on the
	 * MEDIA SERVER side; the JAVA CLIENT call issuing it is fire-and-forget.
	 * An earlier version of this comment claimed the opposite ("matching
	 * {@code stopRecord()}'s own use of {@code stopAndWait}"), which is why
	 * {@link #releaseSingleRecorder(BaseRtpEndpoint, RecorderEndpoint)}
	 * below takes its own {@code outgoingMedia} snapshot rather than
	 * re-reading the field: {@link #release(boolean)} can null the real
	 * {@code outgoingMedia} field (via its own, independent
	 * {@code stopRecorder} completion) while this async completion is still
	 * pending, and reading the field at that later point used to NPE.
	 */
	public synchronized void stopSingleRecord() {
		if (singleRecorder == null) {
			return;
		}
		final RecorderEndpoint toStop = singleRecorder;
		final String requestId = singleRecordRequestId;
		// Captured now, alongside toStop, for the exact same reason toStop
		// itself is captured rather than re-read from the singleRecorder
		// field later: by the time the async continuation below actually
		// runs, release(boolean) may already have nulled the real
		// outgoingMedia field out from under it (see this method's own
		// javadoc, and the NPE this fixed).
		final BaseRtpEndpoint media = outgoingMedia;
		singleRecorder = null;
		singleRecordRequestId = null;
		toStop.stopAndWait(new Continuation<Void>() {
			@Override
			public void onSuccess(Void result) throws Exception {
				log.trace("PARTICIPANT {}: Single-stream recording stopped, requestId {}", KStream.this.uid, requestId);
				releaseSingleRecorder(media, toStop);
			}

			@Override
			public void onError(Throwable cause) throws Exception {
				log.warn("PARTICIPANT {}: Could not stop single-stream recording, requestId {}", KStream.this.uid, requestId, cause);
				releaseSingleRecorder(media, toStop);
			}
		});
	}

	/**
	 * @param media the {@code outgoingMedia} that was live when
	 *              {@link #stopSingleRecord()} issued the stop -- a snapshot,
	 *              NOT necessarily the same object the {@code outgoingMedia}
	 *              field holds by the time this runs (see
	 *              {@link #stopSingleRecord()}'s javadoc). May be {@code null}
	 *              if the participant's whole stream was already torn down
	 *              before this fired; {@code toStop} is still released in
	 *              that case, just never disconnected from a now-gone parent.
	 */
	private void releaseSingleRecorder(BaseRtpEndpoint media, RecorderEndpoint toStop) {
		if (media == null) {
			log.trace("PARTICIPANT {}: outgoingMedia already gone by the time the single-stream recorder stopped -- skipping disconnect", KStream.this.uid);
		} else {
			media.disconnect(toStop, new Continuation<Void>() {
				@Override
				public void onSuccess(Void result) throws Exception {
					log.trace("PARTICIPANT {}: Single-stream recorder disconnected successfully", KStream.this.uid);
				}

				@Override
				public void onError(Throwable cause) throws Exception {
					log.warn("PARTICIPANT {}: Could not disconnect single-stream recorder", KStream.this.uid, cause);
				}
			});
		}
		toStop.release(new Continuation<Void>() {
			@Override
			public void onSuccess(Void result) throws Exception {
				log.trace("PARTICIPANT {}: Single-stream recorder released successfully", KStream.this.uid);
			}

			@Override
			public void onError(Throwable cause) throws Exception {
				log.warn("PARTICIPANT {}: Could not release single-stream recorder", KStream.this.uid, cause);
			}
		});
	}

	/**
	 * @return the OM server's own wall-clock instant (epoch millis, see
	 * {@link #singleRecordStartTime}'s own javadoc) the most recently started
	 * single-stream recording was activated, or {@code null} if
	 * {@link #startSingleRecord(String)} has never succeeded on this stream.
	 * Deliberately a plain, unsynchronized getter -- matching {@link #getRecorder()}/
	 * {@link #getChunkId()} below, not every accessor on this class takes the
	 * monitor just to read a field.
	 */
	public Long getSingleRecordStartTime() {
		return singleRecordStartTime;
	}

	public void remove(final Client c) {
		WebRtcEndpoint point = listeners.remove(c.getUid());
		if (point != null) {
			point.release();
		}
	}

	public void stopBroadcast() {
		kRoom.onStopBroadcast(this);
	}

	public void pauseSharing() {
		releaseListeners();
	}

	private void releaseListeners() {
		log.debug("PARTICIPANT {}: Releasing listeners", uid);
		for (Entry<String, WebRtcEndpoint> entry : listeners.entrySet()) {
			final String inUid = entry.getKey();
			log.trace("PARTICIPANT {}: Released incoming EP for {}", uid, inUid);

			final WebRtcEndpoint ep = entry.getValue();
			outgoingMedia.disconnect(ep, new Continuation<Void>() {
				@Override
				public void onSuccess(Void result) throws Exception {
					log.trace("PARTICIPANT {}: Disconnected successfully incoming EP for {}", KStream.this.uid, inUid);
				}

				@Override
				public void onError(Throwable cause) throws Exception {
					log.warn("PARTICIPANT {}: Could not disconnect incoming EP for {}", KStream.this.uid, inUid);
				}
			});
			ep.release(new Continuation<Void>() {
				@Override
				public void onSuccess(Void result) throws Exception {
					log.trace("PARTICIPANT {}: Released successfully incoming EP for {}", KStream.this.uid, inUid);
				}

				@Override
				public void onError(Throwable cause) throws Exception {
					log.warn("PARTICIPANT {}: Could not release incoming EP for {}", KStream.this.uid, inUid);
				}
			});
		}
		listeners.clear();
	}

	@Override
	public void release(boolean remove) {
		if (outgoingMedia != null) {
			// Must run before outgoingMedia is released below, and before a
			// disconnecting participant's stream disappears out from under an
			// in-progress single-stream recording -- otherwise the recorder
			// leaks and the output file is left unfinalized.
			stopSingleRecord();
			releaseListeners();
			stopRecorder(false, () -> {
				releaseRtp();
				outgoingMedia.release(new Continuation<Void>() {
					@Override
					public void onSuccess(Void result) throws Exception {
						log.trace("PARTICIPANT {}: Released successfully", KStream.this.uid);
					}

					@Override
					public void onError(Throwable cause) throws Exception {
						log.warn("PARTICIPANT {}: Could not release", KStream.this.uid, cause);
					}
				});
				pipeline.release(new Continuation<Void>() {
					@Override
					public void onSuccess(Void result) throws Exception {
						log.trace("PARTICIPANT {}: Released Pipeline", KStream.this.uid);
					}

					@Override
					public void onError(Throwable cause) throws Exception {
						log.warn("PARTICIPANT {}: Could not release Pipeline", KStream.this.uid, cause);
					}
				});
				outgoingMedia = null;
				doRemove(remove);
			});
		} else {
			doRemove(remove);
		}
	}

	private void doRemove(boolean remove) {
		if (remove) {
			processor.release(this, false);
		}
	}

	private void releaseRecorder(Runnable then) {
		outgoingMedia.disconnect(recorder, new Continuation<Void>() {
			@Override
			public void onSuccess(Void result) throws Exception {
				log.trace("PARTICIPANT {}: Recorder disconnected successfully", KStream.this.uid);
			}

			@Override
			public void onError(Throwable cause) throws Exception {
				log.warn("PARTICIPANT {}: Could not disconnect recorder", KStream.this.uid, cause);
			}
		});
		recorder.release(new Continuation<Void>() {
			@Override
			public void onSuccess(Void result) throws Exception {
				log.trace("PARTICIPANT {}: Recorder released successfully", KStream.this.uid);
			}

			@Override
			public void onError(Throwable cause) throws Exception {
				log.warn("PARTICIPANT {}: Could not release recorder", KStream.this.uid, cause);
			}
		});
		recorder = null;
		then.run();
	}

	private void stopRecorder(boolean wait, Runnable then) {
		if (recorder != null) {
			final Continuation<Void> stop = new Continuation<>() {
				@Override
				public void onSuccess(Void result) throws Exception {
					log.trace("PARTICIPANT {}: Recording stopped", KStream.this.uid);
					releaseRecorder(then);
				}

				@Override
				public void onError(Throwable cause) throws Exception {
					log.warn("PARTICIPANT {}: Could not stop recording", KStream.this.uid, cause);
					releaseRecorder(then);
				}
			};
			if (wait) {
				recorder.stopAndWait(stop);
			} else {
				recorder.stop(stop);
			}
		} else {
			then.run();
		}
	}

	private void releaseRtp() {
		if (rtpEndpoint != null) {
			rtpEndpoint.release(new Continuation<Void>() {
				@Override
				public void onSuccess(Void result) throws Exception {
					log.trace("PARTICIPANT {}: RtpEndpoint released successfully", KStream.this.uid);
				}

				@Override
				public void onError(Throwable cause) throws Exception {
					log.warn("PARTICIPANT {}: Could not release RtpEndpoint", KStream.this.uid, cause);
				}
			});
			rtpEndpoint = null;
		}
		sipProcessor.ifPresent(SipStackProcessor::destroy);
		sipProcessor = Optional.empty();
	}

	public void addIceCandidate(IceCandidate candidate, String uid) {
		if (this.uid.equals(uid)) {
			if (!(outgoingMedia instanceof WebRtcEndpoint)) {
				if (!sipClient) {
					log.info("addIceCandidate iceCandidate while not ready yet, uid: {}, candidate: {}", uid, candidate.getCandidate());
					candidatesQueue.add(candidate);
				}
				return;
			}
			((WebRtcEndpoint)outgoingMedia).addIceCandidate(candidate);
		} else {
			WebRtcEndpoint endpoint = listeners.get(uid);
			log.debug("Add candidate for {}, listener found ? {}", uid, endpoint != null);
			if (endpoint != null) {
				endpoint.addIceCandidate(candidate);
			} else {
				log.warn("addIceCandidate iceCandidate could not find endpoint, uid: {}, candidate: {}", uid, candidate.getCandidate());
			}
		}
	}

	public Date getConnectedSince() {
		return connectedSince;
	}

	public Long getRoomId() {
		return kRoom.getRoom().getId();
	}

	MediaPipeline getPipeline() {
		return pipeline;
	}

	public StreamType getStreamType() {
		return streamType;
	}

	public MediaProfileSpecType getProfile() {
		return profile;
	}

	public RecorderEndpoint getRecorder() {
		return recorder;
	}

	public Long getChunkId() {
		return chunkId;
	}

	public Type getType() {
		return type;
	}

	public boolean contains(String uid) {
		return this.uid.equals(uid) || listeners.containsKey(uid);
	}

	@Override
	public String toString() {
		return "KStream [kRoom=" + kRoom + ", streamType=" + streamType + ", profile=" + profile + ", recorder="
				+ recorder + ", outgoingMedia=" + outgoingMedia + ", listeners=" + listeners + ", flowoutFuture="
				+ flowoutFuture + ", chunkId=" + chunkId + ", type=" + type + ", sid=" + sid + ", uid=" + uid + "]";
	}

	void addSipProcessor(long count) {
		if (count > 0) {
			if (sipProcessor.isEmpty()) {
				try {
					sipProcessor = sipManager.createSipStackProcessor(
							randomUUID().toString()
							, kRoom.getRoom()
							, this);
					sipProcessor.ifPresent(SipStackProcessor::register);
				} catch (Exception e) {
					log.error("Unexpected error while creating SipProcessor", e);
				}
			}
		} else {
			if (sipClient) {
				release();
			} else {
				releaseRtp();
			}
		}
	}

	@Override
	public void onRegisterOk() {
		rtpEndpoint = getRtpEndpoint(pipeline);
		if (!sipClient) {
			if (hasAudio) {
				outgoingMedia.connect(rtpEndpoint, MediaType.AUDIO);
			}
			if (hasVideo) {
				outgoingMedia.connect(rtpEndpoint, MediaType.VIDEO);
			}
		}
		sipProcessor.get().invite(kRoom.getRoom(), null);
	}

	@Override
	public void onInviteOk(String sdp, Consumer<String> answerConsumer) {
		String answer = rtpEndpoint.processOffer(sdp.replace("a=sendrecv", sipClient ? "a=sendonly" : "a=recvonly"));
		answerConsumer.accept(answer);
		log.debug(answer);
		if (sipClient) {
			StreamDesc sd = processor.getBySid(sid).getStream(uid);
			try {
				outgoingMedia = rtpEndpoint;
				internalStartBroadcast(sd, sdp);
				notifyOnNewStream(sd);
			} catch (Exception e) {
				log.error("Unexpected error");
			}
		}
	}
}
