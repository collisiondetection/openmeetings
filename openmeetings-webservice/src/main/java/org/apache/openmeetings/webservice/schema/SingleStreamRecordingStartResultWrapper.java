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
package org.apache.openmeetings.webservice.schema;

import org.apache.openmeetings.db.dto.record.SingleStreamRecordingStartResult;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Same purpose as {@link ServiceResultWrapper} -- provide the correct schema
 * response including the wrapping root element + an example -- for the one
 * endpoint (StreamRecordingWebService.startSingle) whose success response
 * carries the extra startTime field on top of the usual message/type.
 */
@Schema(example = """
		{
		  "serviceResult": {
		    "message": "9dbb6907-61fc-42c0-a2b2-5dbfbe053ac6",
		    "type": "SUCCESS",
		    "startTime": 1758012345678
		  }
		}""")
public class SingleStreamRecordingStartResultWrapper {
	private SingleStreamRecordingStartResult serviceResult;

	public SingleStreamRecordingStartResult getServiceResult() {
		return serviceResult;
	}

	public void setServiceResult(SingleStreamRecordingStartResult serviceResult) {
		this.serviceResult = serviceResult;
	}
}
