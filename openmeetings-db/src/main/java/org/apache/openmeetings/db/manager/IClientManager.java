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
import java.util.Map;
import java.util.Set;
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
	// Read-only cluster room-topology snapshot: every currently-known server's
	// own base URL (the same value used to build cross-node redirects, see
	// getServerUrl()) mapped to the set of room ids it presently hosts. Backed
	// by the same Hazelcast-replicated per-server state getServerUrl()'s
	// balancer already reads, so it is accurate from ANY node, not only the
	// one actually asked -- exposed for ClusterInfoWebService, which exists so
	// callers (ais4_join.php, wb_recording_manage.php,
	// participant_recording_manage.php) can look up the right node directly
	// instead of trying every configured node in turn.
	Map<String, Set<Long>> serverRoomsByUrl();
	// Every currently-known server's own base URL mapped to whether it is
	// currently marked draining (excluded from getServerUrl()'s NEW-room
	// balancer pick, but not from rooms it already hosts). Researched
	// 2026-09-18 as the closing piece for a safe elastic-node-fleet scale-down
	// -- see CLAUDE.md's OM node-elasticity research section -- but usable
	// standing on its own for a manual "stop sending this node new classes"
	// operator action even before any fleet automation exists.
	Map<String, Boolean> serverDrainingByUrl();
	// Marks/unmarks THIS node (whichever server actually receives the call --
	// see ClientManager.setDraining()'s own doc) as draining.
	void setDraining(boolean draining);
	// Whether THIS node is currently marked draining.
	boolean isDraining();
	// THIS node's own base URL (or null if it has no ServerInfo entry yet,
	// e.g. single-node/non-clustered mode) -- lets a caller identify which
	// topology entry corresponds to the specific node it just called, since
	// nothing else ties this URL back to the caller's own internal
	// host:port config for that node.
	String selfUrl();
}
