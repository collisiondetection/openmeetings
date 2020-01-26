/* Licensed under the Apache License, Version 2.0 (the "License") http://www.apache.org/licenses/LICENSE-2.0 */
var VideoManager = (function() {
	const self = {};
	let share, inited = false;

	function _onVideoResponse(m) {
		const w = $('#' + VideoUtil.getVid(m.uid))
			, v = w.data()

		v.getPeer().processAnswer(m.sdpAnswer, function (error) {
			if (error) {
				return OmUtil.error(error);
			}
			const vidEls = w.find('audio, video')
				, vidEl = vidEls.length === 1 ? vidEls[0] : null;
			if (vidEl && vidEl.paused) {
				vidEl.play().catch(function(err) {
					if ('NotAllowedError' === err.name) {
						VideoUtil.askPermission(function() {
							vidEl.play();
						});
					}
				});
			}
		});
	}
	function _onBroadcast(msg) {
		const sd = msg.stream
			, uid = sd.uid;
		if (Array.isArray(msg.cleanup)) {
			msg.cleanup.forEach(function(_cuid) {
				_close(_cuid);
			});
		}
		$('#' + VideoUtil.getVid(uid)).remove();
		if(sd.user.rights){
			Video().init(msg); // Set position as 0 for admin
		}else{
			Video().init(msg,userCount); // Set position as 1 for same user
		}
		
		OmUtil.log(uid + ' registered in room');
	}
	function _onShareUpdated(msg) {
		const sd = msg.stream
			, uid = sd.uid
			, w = $('#' + VideoUtil.getVid(uid))
			, v = w.data();
		if (!VideoUtil.isSharing(sd) && !VideoUtil.isRecording(sd)) {
			VideoManager.close(uid, false);
		} else {
			v.stream().activities = sd.activities;
		}
		Sharer.setShareState(VideoUtil.isSharing(sd) ? SHARE_STARTED : SHARE_STOPED);
		Sharer.setRecState(VideoUtil.isRecording(sd) ? SHARE_STARTED : SHARE_STOPED);
	}
	function _onReceive(msg,count) {
		const uid = msg.stream.uid;
		$('#' + VideoUtil.getVid(uid)).remove();
		Video().init(msg,count);
		OmUtil.log(uid + ' receiving video');
	}
	function _onKMessage(m) {
		switch (m.id) {
			case 'clientLeave':
				$(VID_SEL + ' div[data-client-uid="' + m.uid + '"]').each(function() {
					_closeV($(this));
				});
				if (share.data('cuid') === m.uid) {
					share.off('click').hide();
				}				
				break;
			case 'broadcastStopped':
				_close(m.uid, false);				
				break;
			case 'broadcast':
				_onBroadcast(m);
				break;
			case 'videoResponse':
				_onVideoResponse(m);
				break;
			case 'iceCandidate':
				{
					const w = $('#' + VideoUtil.getVid(m.uid))
						, v = w.data()

					v.getPeer().addIceCandidate(m.candidate, function (error) {
						if (error) {
							OmUtil.error('Error adding candidate: ' + error);
							return;
						}
					});
				}
				break;
			case 'newStream':
				_play([m.stream], m.iceServers);
				break;
			case 'shareUpdated':
				_onShareUpdated(m);
				break;
			case 'error':
				OmUtil.error(m.message);
				break;
			case 'rateStudent':
				const w = $(VID_SEL + ' div[data-client-uid="' + m.uid + '"]');        
				w.parent().find('#rating-star')[0].innerHTML = m.rating;
				break;
			default:
				//no-op
		}
	}
	function _onWsMessage(jqEvent, msg) {
		try {
			if (msg instanceof Blob) {
				return; //ping
			}
			const m = jQuery.parseJSON(msg);
			if (!m) {
				return;
			}
			if ('kurento' === m.type && 'test' !== m.mode) {
				OmUtil.info('Received message: ' + msg);
				_onKMessage(m);
			} else if ('mic' === m.type) {
				switch (m.id) {
					case 'activity':
						_userSpeaks(m.uid, m.active);
						break;
					default:
						//no-op
				}
			}
		} catch (err) {
			OmUtil.error(err);
		}
	}
	function _init() {
		Wicket.Event.subscribe('/websocket/message', _onWsMessage);
		VideoSettings.init(Room.getOptions());
		share = $('.room-block .container').find('.icon.shared.ui-button');
		inited = true;
	}
	function _update(c) {
		if (!inited) {
			return;
		}
		const streamMap = {};
		c.streams.forEach(function(sd) {
			streamMap[sd.uid] = sd.uid;
			sd.self = c.self;
			if (VideoUtil.isSharing(sd) || VideoUtil.isRecording(sd)) {
				return;
			}
			const _id = VideoUtil.getVid(sd.uid)
				, av = VideoUtil.hasAudio(sd) || VideoUtil.hasVideo(sd)
				, v = $('#' + _id);
			if (av && v.length === 1) {
				v.data().update(sd);
			} else if (!av && v.length === 1) {
				_closeV(v);
			}
		});
		if (c.uid === Room.getOptions().uid) {
			Room.setRights(c.rights);
			Room.setActivities(c.activities);
			const windows = $(VID_SEL + ' .ui-dialog-content');
			for (let i = 0; i < windows.length; ++i) {
				const w = $(windows[i]);
				w.data().setRights(c.rights);
			}
		}
		$('[data-client-uid="' + c.cuid + '"]').each(function() {
			const sd = $(this).data().stream();
			if (!streamMap[sd.uid]) {
				//not-inited/invalid video window
				_closeV($(this));
			}
		});
	}
	function _closeV(v) {
		if (v.dialog('instance') !== undefined) {
			v.dialog('destroy');
		}
		v.parents('.pod').remove();
		v.remove();
		WbArea.updateAreaClass();
	}
	function _playSharing(sd, iceServers) {
		const m = {stream: sd, iceServers: iceServers};
		let v = $('#' + VideoUtil.getVid(sd.uid))
		if (v.length === 1) {
			v.remove();
		}
		v = Video().init(m);
		VideoUtil.setPos(v, {left: 0, top: 35});
	}
	function _play(streams, iceServers) {
		if (!inited) {
			return;
		}
		var count = 1;
		streams.forEach(function(sd,i) {						
			const m = {stream: sd, iceServers: iceServers};
			if (VideoUtil.isSharing(sd)) {
				VideoUtil.highlight(share
						.attr('title', share.data('user') + ' ' + sd.user.firstName + ' ' + sd.user.lastName + ' ' + share.data('text'))
						.data('uid', sd.uid)
						.data('cuid', sd.cuid)
						.show(), 10);
				share.tooltip().off('click').click(function() {
					_playSharing(sd, iceServers);
				});
				if (Room.getOptions().autoOpenSharing === true) {
					_playSharing(sd, iceServers);
				}
			} else if (VideoUtil.isRecording(sd)) {
				return;
			} else {
				if(sd.user.rights){
					_onReceive(m);
				}else{
					_onReceive(m,count++);
				}			
				
			}
		});
		userCount = count;
	}
	function _close(uid, showShareBtn) {
		const v = $('#' + VideoUtil.getVid(uid));
		if (v.length === 1) {
			_closeV(v);
		}
		if (!showShareBtn && uid === share.data('uid')) {
			share.off('click').hide();
		}
	}
	function _find(uid) {
		return $(VID_SEL + ' div[data-client-uid="' + uid + '"][data-client-type="WEBCAM"]');
	}
	function _userSpeaks(uid, active) {
		const u = $('#user' + uid + ' .audio-activity.ui-icon')
			, v = _find(uid).parent();
		if (active) {
			u.addClass('speaking');
			v.addClass('user-speaks')
		} else {
			u.removeClass('speaking');
			v.removeClass('user-speaks')
		}
	}
	function _refresh(uid, opts) {
		const v = _find(uid);
		if (v.length > 0) {
			v.data().refresh(opts);
		}
	}
	function _mute(uid, mute) {
		const v = _find(uid);
		if (v.length > 0) {
			v.data().mute(mute);
		}
	}
	function _clickMuteOthers(uid) {
		const s = VideoSettings.load();
		if (false !== s.video.confirmMuteOthers) {
			const dlg = $('#muteothers-confirm');
			dlg.dialog({
				buttons: [
					{
						text: dlg.data('btn-ok')
						, click: function() {
							s.video.confirmMuteOthers = !$('#muteothers-confirm-dont-show').prop('checked');
							VideoSettings.save();
							OmUtil.roomAction({action: 'muteOthers', uid: uid});
							$(this).dialog('close');
						}
					}
					, {
						text: dlg.data('btn-cancel')
						, click: function() {
							$(this).dialog('close');
						}
					}
				]
			})
		}
	}
	function _muteOthers(uid) {
		const windows = $(VID_SEL + ' .ui-dialog-content');
		for (let i = 0; i < windows.length; ++i) {
			const w = $(windows[i]);
			w.data().mute('room' + uid !== w.data('client-uid'));
		}
	}
	function _toggleActivity(activity) {
		self.sendMessage({
			id: 'toggleActivity'
			, activity: activity
		});
	}

	self.init = _init;
	self.update = _update;
	self.play = _play;
	self.close = _close;
	self.refresh = _refresh;
	self.mute = _mute;
	self.clickMuteOthers = _clickMuteOthers;
	self.muteOthers = _muteOthers;
	self.toggleActivity = _toggleActivity;
	self.sendMessage = function(_m) {
		OmUtil.sendMessage(_m, {type: 'kurento'});
	}
	self.destroy = function() {
		Wicket.Event.unsubscribe('/websocket/message', _onWsMessage);
	}
	return self;
})();
