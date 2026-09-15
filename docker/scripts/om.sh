#!/bin/bash
# #############################################
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
# #############################################

. ${work}/om_euser.sh
echo "OM server of type ${OM_TYPE} will be run"
CLASSES_HOME=${OM_HOME}/webapps/openmeetings/WEB-INF/classes
export JAVA_OPTS="-Djava.awt.headless=true -Djava.security.egd=file:/dev/./urandom -Dopenjpa.serialization.class.blacklist=* -Dopenjpa.serialization.class.whitelist=[B,java.util,org.apache.openjpa,org.apache.openmeetings.db.entity"
if [ "${OM_TYPE}" == "min" ]; then
	DB_CFG_HOME=${CLASSES_HOME}/META-INF
	cp ${DB_CFG_HOME}/${OM_DB_TYPE}_persistence.xml ${DB_CFG_HOME}/persistence.xml
	case ${OM_DB_TYPE} in
		db2)
			sed -i "s|localhost:50000/openmeet|${OM_DB_HOST}:${OM_DB_PORT}/${OM_DB_NAME}|g" ${DB_CFG_HOME}/persistence.xml
		;;
		mssql)
			sed -i "s|localhost:1433;databaseName=openmeetings|${OM_DB_HOST}:${OM_DB_PORT};databaseName=${OM_DB_NAME}|g" ${DB_CFG_HOME}/persistence.xml
		;;
		mysql)
			# allowPublicKeyRetrieval=true added here (not in the upstream script):
			# useSSL=false is already fixed in the shipped persistence.xml template,
			# and MySQL 8's default caching_sha2_password auth plugin needs either
			# TLS or this flag to hand the client its RSA public key -- without it,
			# the webapp's whole Wicket filter fails to start with "Public Key
			# Retrieval is not allowed", which surfaces in Tomcat's own log as just
			# "One or more Filters failed to start" (real cause only visible in
			# logs/openmeetings.log). The all-in-one (OM_TYPE=all) install path in
			# om_install.sh already patches this same flag in for its own local
			# MySQL setup -- this closes the identical gap for the external-DB
			# (min) path, which had no equivalent fix.
			sed -i "s|localhost:3306/openmeetings?|${OM_DB_HOST}:${OM_DB_PORT}/${OM_DB_NAME}?serverTimezone=${SERVER_TZ}\&amp;allowPublicKeyRetrieval=true\&amp;|g" ${DB_CFG_HOME}/persistence.xml
		;;
		postgresql)
			sed -i "s|localhost:5432/openmeetings|${OM_DB_HOST}:${OM_DB_PORT}/${OM_DB_NAME}|g" ${DB_CFG_HOME}/persistence.xml
		;;
	esac
	sed -i "s/Username=/Username=${OM_DB_USER}/g; s/Password=/Password=${OM_DB_PASS}/g" ${DB_CFG_HOME}/persistence.xml
	if [ ! -d "${OM_DATA_DIR}" ]; then
		echo "Make data dir ${OM_DATA_DIR}"
		mkdir "${OM_DATA_DIR}"
	fi
	# mkdir above runs as root; DAEMON_USER (the user catalina.sh actually runs as,
	# see the sudo at the bottom of this script) needs write access to create its
	# own subdirectories under here (streams/, upload/, ...) on first use. Without
	# this, a fresh volume leaves DAEMON_USER unable to create any new top-level
	# subdirectory here -- silently (mkdirs() returns false, doesn't throw),
	# surfacing later as a confusing NoSuchFileException from deep inside whatever
	# Java code tried to write the first file. Shallow, not -R: this only needs to
	# unblock creating new subdirectories, not rewrite an entire potentially large,
	# already-populated production volume's ownership on every boot.
	chown ${DAEMON_USER} "${OM_DATA_DIR}"
	sed -i "s|ws://127.0.0.1:8888/kurento|${OM_KURENTO_WS_URL}|g" ${CLASSES_HOME}/openmeetings.properties

	export CATALINA_OPTS="-DDATA_DIR=${OM_DATA_DIR}"
else
	export GST_REGISTRY=/tmp/.gstcache
	sudo ln -nfs /usr/lib/x86_64-linux-gnu/libopenh264.so.4 /usr/lib/x86_64-linux-gnu/libopenh264.so.0
	service kurento-media-server start
fi
if [ -n "${TURN_URL}" ]; then
	sed -i "s|kurento.turn.url=|kurento.turn.url=${TURN_URL}|g" ${CLASSES_HOME}/openmeetings.properties
fi
if [ -n "${TURN_USER}" ]; then
	sed -i "s|kurento.turn.user=|kurento.turn.user=${TURN_USER}|g" ${CLASSES_HOME}/openmeetings.properties
fi
if [ -n "${TURN_PASS}" ]; then
	sed -i "s|kurento.turn.secret=|kurento.turn.secret=${TURN_PASS}|g" ${CLASSES_HOME}/openmeetings.properties
fi
# SSR-2 (AUDIT_FINDINGS.md): the session cookie's SameSite policy is a property
# of the DEPLOYMENT's transport, not of OM. context.xml ships sameSiteCookies
# ="None" so a cross-origin iframe embed (mod_tutorship embeds an OM room inside
# Moodle) keeps session continuity -- but SameSite=None is VALID only together
# with Secure, which Tomcat adds only for HTTPS requests. Over plain HTTP the
# browser REJECTS a None-without-Secure cookie outright (it does NOT fall back
# to a default), so JSESSIONID is never retained, every request lands in a fresh
# session, the room's stateful WebSocket cannot persist, and the room enters an
# endless ROOM_ENTER/ROOM_LEAVE page-reload loop (see context.xml's own comment).
# HTTPS-fronted staging/production leave this unset (keeping None); a plain-HTTP
# deployment (e.g. local dev on :5080) sets OM_SAMESITE_COOKIES=Lax. Anchored to
# the <CookieProcessor> element so the explanatory comment above it is untouched.
if [ -n "${OM_SAMESITE_COOKIES}" ]; then
	sed -i "s|\(<CookieProcessor[^>]*sameSiteCookies=\)\"[^\"]*\"|\1\"${OM_SAMESITE_COOKIES}\"|" ${OM_HOME}/webapps/openmeetings/META-INF/context.xml
	echo "Set session cookie SameSite policy to ${OM_SAMESITE_COOKIES}"
fi
echo Current max open files is $(su nobody --shell /bin/bash --command "ulimit -n")
cd ${OM_HOME}
# SINGLE_STREAM_BATCH_JOBDEF: without preserving it, sudo strips it before the
# JVM starts, so System.getenv("SINGLE_STREAM_BATCH_JOBDEF") returns null and
# SingleStreamConversionSubmitter silently falls back to the shim's default
# (the MAIN job definition) instead of the dedicated single-stream one. Found
# 2026-09-14: functionally harmless today (both job definitions are
# byte-identical in image/resources/queue), but every single-stream conversion
# was being cost/log-attributed to the wrong Batch job definition.
sudo --preserve-env=JAVA_OPTS --preserve-env=CATALINA_OPTS --preserve-env=SINGLE_STREAM_BATCH_JOBDEF -u ${DAEMON_USER} HOME=/tmp ${OM_HOME}/bin/catalina.sh run

