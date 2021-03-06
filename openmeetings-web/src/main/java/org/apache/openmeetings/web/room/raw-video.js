/* Licensed under the Apache License, Version 2.0 (the "License") http://www.apache.org/licenses/LICENSE-2.0 */
var Video = (function() {
	const self = {}
		, AudioCtx = window.AudioContext || window.webkitAudioContext;
	let sd, v, vc, t, f, size, vol, slider, handle, video, iceServers
		, lastVolume = 50, muted = false
		, lm, level, userSpeaks = false, muteOthers,moderatorVidPos=false;

	function _resizeDlgArea(_w, _h) {
		if (Room.getOptions().interview) {
			VideoUtil.setPos(v, VideoUtil.getPos());
		} else if (v.dialog('instance')) {
			v.dialog('option', 'width', _w).dialog('option','height', _h);
		}
	}
	function _micActivity(speaks) {
		if (speaks !== userSpeaks) {
			userSpeaks = speaks;
			OmUtil.sendMessage({type: 'mic', id: 'activity', active: speaks});
		}
	}
	function _getScreenStream(msg, callback) {
		function __handleScreenError(err) {
			VideoManager.sendMessage({id: 'errorSharing'});
			Sharer.setShareState(SHARE_STOPED);
			Sharer.setRecState(SHARE_STOPED);
			OmUtil.error(err);
		}
		const b = kurentoUtils.WebRtcPeer.browser;
		let promise, cnts;
		if (VideoUtil.isEdge(b) && b.major > 16) {
			cnts = {
				video: true
			};
			promise = navigator.getDisplayMedia(cnts);
		} else if (b.name === 'Firefox') {
			// https://mozilla.github.io/webrtc-landing/gum_test.html
			cnts = Sharer.baseConstraints(sd);
			cnts.video.mediaSource = sd.shareType;
			promise = navigator.mediaDevices.getUserMedia(cnts);
		} else if (VideoUtil.isChrome(b)) {
			cnts = {
				video: true
			};
			promise = navigator.mediaDevices.getDisplayMedia(cnts);
		} else {
			promise = new Promise(() => {
				Sharer.close();
				throw 'Screen-sharing is not supported in ' + b.name + '[' + b.major + ']';
			});
		}
		promise.then(function(stream) {
			__createVideo();
			callback(msg, cnts, stream);
		}).catch(__handleScreenError);
	}
	function _getVideoStream(msg, callback) {
		VideoSettings.constraints(sd, function(cnts) {
			if ((VideoUtil.hasVideo(sd) && !cnts.video) || (VideoUtil.hasAudio(sd) && !cnts.audio)) {
				VideoManager.sendMessage({
					id : 'devicesAltered'
					, uid: sd.uid
					, audio: !!cnts.audio
					, video: !!cnts.video
				});
			}
			if (!cnts.audio && !cnts.video) {
				OmUtil.error('Requested devices are not available');
				VideoManager.close(sd.uid)
				return;
			}
			navigator.mediaDevices.getUserMedia(cnts)
				.then(function(stream) {
					let _stream = stream;
					__createVideo();
					if (stream.getAudioTracks().length !== 0) {
						vol.show();
						lm = vc.find('.level-meter');
						lm.show();
						const data = {};
						data.aCtx = new AudioCtx();
						data.gainNode = data.aCtx.createGain();
						data.analyser = data.aCtx.createAnalyser();
						data.aSrc = data.aCtx.createMediaStreamSource(stream);
						data.aSrc.connect(data.gainNode);
						data.gainNode.connect(data.analyser);
						if (VideoUtil.isEdge()) {
							data.analyser.connect(data.aCtx.destination);
						} else {
							data.aDest = data.aCtx.createMediaStreamDestination();
							data.analyser.connect(data.aDest);
							data.aSrc.origStream = stream;
							_stream = data.aDest.stream;
							stream.getVideoTracks().forEach(function(track) {
								_stream.addTrack(track);
							});
						}
						video.data(data);
						_handleVolume(lastVolume);
					}
					callback(msg, cnts, _stream);
				})
				.catch(function(err) {
					VideoManager.sendMessage({
						id : 'devicesAltered'
						, uid: sd.uid
						, audio: false
						, video: false
					});
					VideoManager.close(sd.uid);
					if ('NotReadableError' === err.name) {
						OmUtil.error('Camera/Microphone is busy and can\'t be used');
					} else {
						OmUtil.error(err);
					}
				});
		});
	}
	function __createSendPeer(msg, cnts, stream) {
		const options = {
			videoStream: stream
			, mediaConstraints: cnts
			, onicecandidate: self.onIceCandidate
		};
		if (!VideoUtil.isSharing(sd)) {
			options.localVideo = video[0];
		}
		const data = video.data();
		data.rtcPeer = new kurentoUtils.WebRtcPeer.WebRtcPeerSendonly(
			VideoUtil.addIceServers(options, msg)
			, function (error) {
				if (error) {
					return OmUtil.error(error);
				}
				if (data.analyser) {
					level = MicLevel();
					level.meter(data.analyser, lm, _micActivity, OmUtil.error);
				}
				this.generateOffer(function(error, offerSdp) {
					if (error) {
						return OmUtil.error('Sender sdp offer error ' + error);
					}
					OmUtil.log('Invoking Sender SDP offer callback function');
					VideoManager.sendMessage({
						id : 'broadcastStarted'
						, uid: sd.uid
						, sdpOffer: offerSdp
					});
					if (VideoUtil.isSharing(sd)) {
						Sharer.setShareState(SHARE_STARTED);
					}
					if (VideoUtil.isRecording(sd)) {
						Sharer.setRecState(SHARE_STARTED);
					}
				});
			});
	}
	function _createSendPeer(msg) {
		if (VideoUtil.isSharing(sd) || VideoUtil.isRecording(sd)) {
			_getScreenStream(msg, __createSendPeer);
		} else {
			_getVideoStream(msg, __createSendPeer);
		}
	}
	function _createResvPeer(msg) {
		__createVideo();
		const options = VideoUtil.addIceServers({
			remoteVideo : video[0]
			, onicecandidate : self.onIceCandidate
		}, msg);
		const data = video.data();
		data.rtcPeer = new kurentoUtils.WebRtcPeer.WebRtcPeerRecvonly(
			options
			, function(error) {
				if (!this.cleaned && error) {
					return OmUtil.error(error);
				}
				this.generateOffer(function(error, offerSdp) {
					if (!this.cleaned && error) {
						return OmUtil.error('Receiver sdp offer error ' + error);
					}
					OmUtil.log('Invoking Receiver SDP offer callback function');
					VideoManager.sendMessage({
						id : 'addListener'
						, sender: sd.uid
						, sdpOffer: offerSdp
					});
				});
			});
	}
	function _handleMicStatus(state) {
		if (!f || !f.is(':visible')) {
			return;
		}
		if (state) {
			f.find('.off').hide();
			f.find('.on').show();
			f.addClass('ui-state-highlight');
			t.addClass('ui-state-highlight');
		} else {
			f.find('.off').show();
			f.find('.on').hide();
			f.removeClass('ui-state-highlight');
			t.removeClass('ui-state-highlight');
		}
	}
	function _handleVolume(val) {
		handle.text(val);
		if (sd.self) {
			const data = video.data();
			if (data.gainNode) {
				data.gainNode.gain.value = val / 100;
			}
		} else {
			video[0].volume = val / 100;
		}
		const ico = vol.find('.ui-icon');
		if (val > 0 && ico.hasClass('ui-icon-volume-off')) {
			ico.toggleClass('ui-icon-volume-off ui-icon-volume-on');
			vol.removeClass('ui-state-error');
			_handleMicStatus(true);
		} else if (val === 0 && ico.hasClass('ui-icon-volume-on')) {
			ico.toggleClass('ui-icon-volume-on ui-icon-volume-off');
			vol.addClass('ui-state-error');
			_handleMicStatus(false);
		}
	}
	function _mute(mute) {
		if (!slider) {
			return;
		}
		muted = mute;
		if (mute) {
			const val = slider.slider('option', 'value');
			if (val > 0) {
				lastVolume = val;
			}
			slider.slider('option', 'value', 0);
			_handleVolume(0);
		} else {
			slider.slider('option', 'value', lastVolume);
			_handleVolume(lastVolume);
		}
	}
	function _initContainer(_id, name, opts) {
		let contSel;
		if (opts.interview) {
			const area = $('.pod-area');
			const contId = uuidv4();
			contSel = '#' + contId;
			area.append($('<div class="pod"></div>').attr('id', contId));
			WbArea.updateAreaClass();
		} else {
			contSel = '.room-block .container .video-block';
		}
		$(contSel).append(OmUtil.tmpl('#user-video', _id)
				.attr('title', name)
				.attr('data-client-uid', sd.cuid)
				.attr('data-client-type', sd.type)
				.data(self));
		return contSel;
	}
	function _initDialog(v, opts) {
		if (opts.interview) {
			v.dialog('option', 'draggable', false);
			v.dialog('option', 'resizable', false);
			v.dialogExtend({
				closable: false
				, collapsable: false
				, dblclick: false
			});
			$('.pod-area').sortable('refresh');
		} else {
			v.dialog('option', 'draggable', false);
			v.dialog('option', 'resizable', false);
			if (VideoUtil.isSharing(sd)) {
				v.on('dialogclose', function() {
					VideoManager.close(sd.uid, true);
				});
			}
			v.dialogExtend({				
				closable: VideoUtil.isSharing(sd)
				, collapsable: false
				, dblclick: false			
			});
		}
	}
	function _initCamDialog() {
		v.parent().find('.ui-dialog-titlebar-buttonpane')
			.append($('#video-volume-btn').children().clone())
			.append($('#video-refresh-btn').children().clone());
		if(!sd.user.rights){
			v.parent().find('.ui-dialog-titlebar-buttonpane')
				.append($('#rating-btn').children().clone());			
			v.parent().find('#rating-star')[0].innerHTML = sd.user.rating;
		}
		const volume = v.parent().find('.dropdown-menu.video.volume');
		slider = v.parent().find('.slider');
		vol = v.parent().find('.ui-dialog-titlebar-volume')
			.on('mouseenter', function(e) {
				e.stopImmediatePropagation();
				volume.toggle();
			})
			.click(function(e) {
				e.stopImmediatePropagation();
				OmUtil.roomAction({action: 'mute', uid: sd.uid, mute: !muted});
				_mute(!muted);
				volume.hide();
				return false;
			}).dblclick(function(e) {
				e.stopImmediatePropagation();
				return false;
			});
		v.parent().find('.ui-dialog-titlebar-refresh')
			.click(function(e) {
				e.stopImmediatePropagation();
				_refresh();
				return false;
			}).dblclick(function(e) {
				e.stopImmediatePropagation();
				return false;
			});
		volume.on('mouseleave', function() {
			$(this).hide();
		});
		// Add Rating Click logic
		v.parent().find('.ui-dialog-titlebar-rating')
			.click(function(e){
				e.stopImmediatePropagation();
				console.log("ererere",sd);				
				OmUtil.roomAction({action: 'rateStudent', uid: sd.cuid});
				return false;
			});

		handle = v.parent().find('.slider .handle');
		slider.slider({
			orientation: 'vertical'
			, range: 'min'
			, min: 0
			, max: 100
			, value: lastVolume
			, create: function() {
				handle.text($(this).slider('value'));
			}
			, slide: function(event, ui) {
				_handleVolume(ui.value);
			}
		});
		vol.hide();
	}
	function _init(msg,count) {
		sd = msg.stream;
		iceServers = msg.iceServers;
		sd.activities = sd.activities.sort();		
		sd.width = 160;
		sd.height = 145;
		size = {width: sd.width, height: sd.height};
		const _id = VideoUtil.getVid(sd.uid)
			, name = sd.user.displayName
			, _w = sd.width
			, _h = sd.height
			, isSharing = VideoUtil.isSharing(sd)
			, isRecording = VideoUtil.isRecording(sd)
			, opts = Room.getOptions();
		sd.self = sd.cuid === opts.uid;
		const contSel = _initContainer(_id, name, opts);
		if(sd.user.rights && !moderatorVidPos){
			moderatorVidPos= true;			
		}		
		v = $('#' + _id);
		f = v.find('.footer');
		if (!sd.self && isSharing) {
			Sharer.close();
		}
		if (sd.self && (isSharing || isRecording)) {
			v.hide();
		} else {
			v.dialog({
				classes: {
					'ui-dialog': 'ui-corner-all video user-video' + (opts.showMicStatus ? ' mic-status' : '') + (sd.user.rights ? ' ui-dialog-admin' : '')
					, 'ui-dialog-titlebar': 'ui-corner-all' + (opts.showMicStatus ? ' ui-state-highlight' : '')					
				}
				, width: _w
				, minWidth: 40
				, minHeight: 50
				, autoOpen: true
				, modal: false
				, appendTo: contSel
				, hasRights: sd.user.rights
			});
			_initDialog(v, opts);
		}
		if (!isSharing && !isRecording) {
			_initCamDialog();
		}
		t = v.parent().find('.ui-dialog-titlebar').attr('title', name);
		v.on('remove', _cleanup);
		vc = v.find('.video');
		vc.width(_w).height(_h);
		muteOthers = vc.find('.mute-others');

		_refresh(msg);
		if(moderatorVidPos){
			vidLeft = 0;
		}else{			
			vidLeft = count*200;
		}
		if (!isSharing && !isRecording) {
			//VideoUtil.setPos(v, VideoUtil.getPos(VideoUtil.getRects(VID_SEL), sd.width, sd.height + 25));
			VideoUtil.setPos(v,{left:vidLeft,top:0});
		}	
				
		return v;
	}
	function _update(_c) {
		const prevA = sd.activities;
		sd.activities = _c.activities.sort();
		sd.user.firstName = _c.user.firstName;
		sd.user.lastName = _c.user.lastName;
		const name = sd.user.displayName;
		v.dialog('option', 'title', name).parent().find('.ui-dialog-titlebar').attr('title', name);
		const same = prevA.length === sd.activities.length && prevA.every(function(value, index) { return value === sd.activities[index]})
		if (sd.self && !same) {
			_refresh();
		}
	}
	function __createVideo() {
		const _id = VideoUtil.getVid(sd.uid);
		const hasVideo = VideoUtil.hasVideo(sd) || VideoUtil.isSharing(sd) || VideoUtil.isRecording(sd);
		// _resizeDlgArea(hasVideo ? size.width : 120
		// 	, hasVideo ? size.height : 90);
		video = $(hasVideo ? '<video>' : '<audio>').attr('id', 'vid' + _id)
			.width(vc.width()).height(vc.height())
			.prop('autoplay', true).prop('controls', false);
		if (hasVideo) {
			vc.removeClass('audio-only').css('background-image', '');;
			vc.parents('.ui-dialog').removeClass('audio-only');
			video.attr('poster', sd.user.pictureUri);
		} else {
			vc.parents('.ui-dialog').addClass('audio-only');
			vc.addClass('audio-only').css('background-image', 'url(' + sd.user.pictureUri + ')');
		}
		vc.append(video);
		if (vol) {
			if (VideoUtil.hasAudio(sd)) {
				vol.show();
				_mute(muted);
			} else {
				vol.hide();
				v.parent().find('.dropdown-menu.video.volume').hide();
			}
		}
	}
	function _refresh(_msg) {
		const msg = _msg || {iceServers: iceServers};
		_cleanup();
		const hasAudio = VideoUtil.hasAudio(sd);
		if (sd.self) {
			_createSendPeer(msg);
			_handleMicStatus(hasAudio);
		} else {
			_createResvPeer(msg);
		}
	}
	function _setRights() {
		if (Room.hasRight(['superModerator', 'moderator', 'muteOthers']) && VideoUtil.hasAudio(sd)) {
			muteOthers.addClass('enabled').click(function() {
				VideoManager.clickMuteOthers(sd.uid);
			});
		} else {
			muteOthers.removeClass('enabled').off();
		}
	}
	function _cleanup() {
		OmUtil.log('Disposing participant ' + sd.uid);
		if (video && video.length > 0) {
			const data = video.data();
			if (data.analyser) {
				VideoUtil.disconnect(data.analyser);
				data.analyser = null;
			}
			if (data.gainNode) {
				VideoUtil.disconnect(data.gainNode);
				data.gainNode = null;
			}
			if (data.aSrc) {
				VideoUtil.cleanStream(data.aSrc.mediaStream);
				VideoUtil.cleanStream(data.aSrc.origStream);
				VideoUtil.disconnect(data.aSrc);
				data.aSrc = null;
			}
			if (data.aDest) {
				VideoUtil.disconnect(data.aDest);
				data.aDest = null;
			}
			if (data.aCtx) {
				if (data.aCtx.destination) {
					VideoUtil.disconnect(data.aCtx.destination);
				}
				data.aCtx.close();
				data.aCtx = null;
			}
			video.attr('id', 'dummy');
			const vidNode = video[0];
			VideoUtil.cleanStream(vidNode.srcObject);
			vidNode.srcObject = null;
			vidNode.parentNode.removeChild(vidNode);

			VideoUtil.cleanPeer(data.rtcPeer);
			video = null;
		}
		if (lm && lm.length > 0) {
			_micActivity(false);
			lm.hide();
			muteOthers.removeClass('enabled').off();
		}
		if (level) {
			level.dispose();
			level = null;
		}
		vc.find('audio,video').remove();
	}
	function _reattachStream() {
		if (video && video.length > 0) {
			const data = video.data();
			if (data.rtcPeer) {
				video[0].srcObject = sd.self ? data.rtcPeer.getLocalStream() : data.rtcPeer.getRemoteStream();
			}
		}
	}
			
	self.update = _update;
	self.refresh = _refresh;
	self.mute = _mute;
	self.isMuted = function() { return muted; };
	self.init = _init;
	self.stream = function() { return sd; };
	self.setRights = _setRights;
	self.getPeer = function() { return video ? video.data().rtcPeer : null; };
	self.onIceCandidate = function(candidate) {
		const opts = Room.getOptions();
		OmUtil.log('Local candidate ' + JSON.stringify(candidate));
		VideoManager.sendMessage({
			id: 'onIceCandidate'
			, candidate: candidate
			, uid: sd.uid
			, luid: sd.self ? sd.uid : opts.uid
		});
	};
	self.reattachStream = _reattachStream;
	return self;
});
