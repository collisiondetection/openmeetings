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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlRootElement;

import org.apache.openmeetings.db.dto.basic.ServiceResult;

/**
 * The wire response for {@code ClusterInfoWebService.topology} -- a
 * {@link ServiceResult} widened with every currently-known server's own base
 * URL mapped to the room ids it presently hosts (an empty list means that
 * server is idle). Same subclass-for-one-operation pattern as
 * {@link RtpParticipantJoinResponse}.
 *
 * <p>{@code @XmlRootElement(name = "serviceResult")} is redeclared here (JAXB's
 * annotation is not {@code @Inherited}) so CXF keeps producing the same
 * wrapper element name a {@link ServiceResult} produces, rather than one
 * derived from this class's own name.
 */
@XmlRootElement(name = "serviceResult")
@XmlAccessorType(XmlAccessType.FIELD)
public class ClusterTopologyResponse extends ServiceResult {
	private static final long serialVersionUID = 1L;
	private List<ServerRoomInfo> servers;

	public ClusterTopologyResponse() {
		//def constructor
	}

	public ClusterTopologyResponse(String message, Type type) {
		super(message, type);
	}

	public ClusterTopologyResponse(Map<String, Set<Long>> serverRoomsByUrl, Map<String, Boolean> serverDrainingByUrl, String selfUrl) {
		super("", Type.SUCCESS);
		this.servers = new ArrayList<>();
		for (Map.Entry<String, Set<Long>> e : serverRoomsByUrl.entrySet()) {
			boolean draining = Boolean.TRUE.equals(serverDrainingByUrl.get(e.getKey()));
			boolean isSelf = e.getKey().equals(selfUrl);
			servers.add(new ServerRoomInfo(e.getKey(), new ArrayList<>(e.getValue()), draining, isSelf));
		}
	}

	public List<ServerRoomInfo> getServers() {
		return servers;
	}

	public void setServers(List<ServerRoomInfo> servers) {
		this.servers = servers;
	}

	@XmlAccessorType(XmlAccessType.FIELD)
	public static class ServerRoomInfo {
		private String url;
		private List<Long> roomIds;
		private boolean draining;
		private boolean isSelf;

		public ServerRoomInfo() {
			//def constructor
		}

		public ServerRoomInfo(String url, List<Long> roomIds, boolean draining, boolean isSelf) {
			this.url = url;
			this.roomIds = roomIds;
			this.draining = draining;
			this.isSelf = isSelf;
		}

		public String getUrl() {
			return url;
		}

		public void setUrl(String url) {
			this.url = url;
		}

		public List<Long> getRoomIds() {
			return roomIds;
		}

		public void setRoomIds(List<Long> roomIds) {
			this.roomIds = roomIds;
		}

		public boolean isDraining() {
			return draining;
		}

		public void setDraining(boolean draining) {
			this.draining = draining;
		}

		public boolean isSelf() {
			return isSelf;
		}

		public void setSelf(boolean isSelf) {
			this.isSelf = isSelf;
		}
	}
}
