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
package org.apache.openmeetings.db.manager;

import java.util.Collection;
import java.util.stream.Stream;

import org.apache.openmeetings.db.entity.basic.Client;

public interface IClientManager {
	Client get(String uid);
	Client getBySid(String sid);
	String uidBySid(String sid);
	Stream<Client> stream();
	Stream<Client> streamByRoom(Long roomId);
	Collection<Client> listByUser(Long userId);
	Client update(Client c);
	void exit(Client c);
	// Exposed on the interface so the browser-less RTP-participant manager
	// (openmeetings-mediaserver, which depends on this interface, not on the
	// concrete ClientManager in openmeetings-web) can register a synthetic
	// participant the same way TimerService's SIP path does. Both are already
	// implemented (public) on the sole implementor, ClientManager, and this
	// only widens their visibility to the interface -- no behavioural change.
	void add(Client c);
	int addToRoom(Client c);
}
