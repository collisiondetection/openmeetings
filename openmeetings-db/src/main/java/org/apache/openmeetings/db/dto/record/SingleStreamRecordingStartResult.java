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
package org.apache.openmeetings.db.dto.record;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlRootElement;

import org.apache.openmeetings.db.dto.basic.ServiceResult;

/**
 * {@link ServiceResult}, widened with the OM-server-clock instant a
 * single-participant recording was activated. Returned ONLY by
 * {@code StreamRecordingWebService.startSingle} -- every other endpoint in
 * the webservice module keeps returning a plain {@link ServiceResult}, so
 * this deliberately does NOT touch that shared class (used by ~10 unrelated
 * webservices) and instead subclasses it for this one operation, the same
 * way a specific JAX-RS operation's response is ordinarily specialised in
 * this kind of codebase.
 *
 * Purely additive on the wire: {@code message}/{@code type} keep their
 * existing bare-request-id / SUCCESS-or-ERROR meaning, in the same position,
 * completely unchanged -- so the already-shipped "record the student for AI
 * analysis" consumer, which only ever reads those two fields, keeps working
 * unmodified whether or not it's ever updated to also read {@code startTime}.
 *
 * {@code @XmlRootElement} is NOT inherited from {@code ServiceResult} (Java
 * annotations aren't inherited unless meta-annotated {@code @Inherited}, and
 * JAXB's isn't), so it's redeclared here with the identical root name --
 * without this, the JSON/XML wrapper element CXF produces for this specific
 * endpoint would silently change from {@code "serviceResult"} to something
 * derived from this class's own name, which would be a real, if easy to
 * miss, breaking change for the existing consumer.
 *
 * {@code startTime} is milliseconds since the Unix epoch, the OM server's
 * own wall clock ({@code System.currentTimeMillis()}) at the moment the
 * recording was activated -- the SAME clock domain as the whiteboard
 * recording JSONL log's own "ts" fields (see {@code WbRecordingManager} in
 * openmeetings-service), so a caller synchronizing whiteboard events against
 * this recording can compare the two directly, with no timezone/offset
 * conversion. Left {@code null} when {@code type} is {@code ERROR} -- nothing
 * started, so there is no instant to report.
 */
@XmlRootElement(name = "serviceResult")
@XmlAccessorType(XmlAccessType.FIELD)
public class SingleStreamRecordingStartResult extends ServiceResult {
	private static final long serialVersionUID = 1L;
	private Long startTime;

	public SingleStreamRecordingStartResult() {
		//def constructor
	}

	public SingleStreamRecordingStartResult(String message, Type type, Long startTime) {
		super(message, type);
		this.startTime = startTime;
	}

	public Long getStartTime() {
		return startTime;
	}

	public void setStartTime(Long startTime) {
		this.startTime = startTime;
	}
}
