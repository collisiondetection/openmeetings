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
package org.apache.openmeetings.db.dto.room;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlRootElement;

import org.apache.openmeetings.db.dto.basic.ServiceResult;

/**
 * The wire response for {@code RtpParticipantWebService.join} -- a
 * {@link ServiceResult} ({@code message}/{@code type} for the human-facing
 * outcome) widened with the fields the caller actually needs back: the session
 * id (to call {@code end} later) and Kurento's SDP answers (which the caller's
 * media bridge must be pointed at). Same subclass-for-one-operation pattern as
 * {@link org.apache.openmeetings.db.dto.record.SingleStreamRecordingStartResult}.
 *
 * <p>{@code @XmlRootElement(name = "serviceResult")} is redeclared here (JAXB's
 * annotation is not {@code @Inherited}) so CXF keeps producing the same wrapper
 * element name a {@link ServiceResult} produces, rather than one derived from
 * this class's own name.
 *
 * <p>All SDP fields are {@code null} when {@code type} is {@code ERROR} -- there
 * is nothing to send. {@code reverseSdpAnswer} is also {@code null} on success
 * when no reverse leg was requested, or when it was requested but deferred
 * because no real participant was in the room yet.
 */
@XmlRootElement(name = "serviceResult")
@XmlAccessorType(XmlAccessType.FIELD)
public class RtpParticipantJoinResponse extends ServiceResult {
	private static final long serialVersionUID = 1L;
	private String sessionId;
	private String sdpAnswer;
	private String reverseSdpAnswer;
	private String clientUid;
	private String streamUid;

	public RtpParticipantJoinResponse() {
		//def constructor
	}

	public RtpParticipantJoinResponse(String message, Type type) {
		super(message, type);
	}

	public RtpParticipantJoinResponse(RtpParticipantJoinResult r) {
		super("", Type.SUCCESS);
		this.sessionId = r.getSessionId();
		this.sdpAnswer = r.getSdpAnswer();
		this.reverseSdpAnswer = r.getReverseSdpAnswer();
		this.clientUid = r.getClientUid();
		this.streamUid = r.getStreamUid();
	}

	public String getSessionId() {
		return sessionId;
	}

	public void setSessionId(String sessionId) {
		this.sessionId = sessionId;
	}

	public String getSdpAnswer() {
		return sdpAnswer;
	}

	public void setSdpAnswer(String sdpAnswer) {
		this.sdpAnswer = sdpAnswer;
	}

	public String getReverseSdpAnswer() {
		return reverseSdpAnswer;
	}

	public void setReverseSdpAnswer(String reverseSdpAnswer) {
		this.reverseSdpAnswer = reverseSdpAnswer;
	}

	public String getClientUid() {
		return clientUid;
	}

	public void setClientUid(String clientUid) {
		this.clientUid = clientUid;
	}

	public String getStreamUid() {
		return streamUid;
	}

	public void setStreamUid(String streamUid) {
		this.streamUid = streamUid;
	}
}
