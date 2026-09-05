# syntax=docker/dockerfile:1
# Multi-stage build for a locally-patched OpenMeetings image.
#
# Unlike the official apache/openmeetings image (openmeetings/openmeetings-docker,
# tag 9.1.1 -- which despite the tag name builds OM_VERSION=9.1.0 from Apache's
# release archive), this Dockerfile builds the release tarball FROM THIS CHECKOUT'S
# OWN SOURCE via Maven, so a patch applied here is what actually ends up in the
# image -- no dependency on pushing a fake release to archive.apache.org or
# Apache's own CI.
#
# Runtime stage (packages, entrypoint, install-time env vars) is copied verbatim
# from the real openmeetings-docker repo's 9.1.1-tagged Dockerfile so this stays a
# faithful match for what production actually runs, with only the source step
# swapped from "download a release tarball" to "build one from this checkout".

# ---- Build stage ----
FROM maven:3-eclipse-temurin-21 AS builder
WORKDIR /build
COPY . .
# Delete this Dockerfile's own build tooling from the BUILDER STAGE's copy
# before Maven ever runs -- it isn't part of the reactor, and more
# importantly isn't Apache-licensed source, so leaving it in place makes
# Apache RAT's license-header check (bound to the root pom.xml, runs during
# `mvn install`) scan it and fail with "UNAPPROVED" counters. Confirmed by
# name, not guessed: the build log's own "Files with unapproved licenses:"
# line named the exact file both times this was gotten wrong (see below).
#
# Deliberately NOT solved with .dockerignore, which was tried first and
# reverted: .dockerignore excludes a path from the WHOLE build context, but
# the runtime stage below also needs `docker/scripts/*.sh` via its own COPY
# -- excluding docker/ from the context broke that COPY instead
# ("lstat /docker/scripts: no such file or directory"). Deleting it here,
# inside the builder stage only, leaves the original context (and the
# runtime stage's own COPY from it) untouched.
#
# Three earlier, WRONG fixes for the RAT failure, all reverted -- recorded
# so they aren't tried again:
#   1. `-Pquick` skipped RAT, but that profile also silently swaps
#      openmeetings-server's assembly descriptor to quick.xml, whose only
#      <format> is a bare directory -- no tar.gz at all. BUILD SUCCESS, no
#      artifact.
#   2. `-Dom.quick.build=true` (the property the quick profile itself sets)
#      skips RAT without touching the assembly descriptor -- but that same
#      property ALSO gates the maven-dependency-plugin's `get-tomcat` unpack
#      execution (shared plugin-level <skip> config in openmeetings-server's
#      pom.xml), so the resulting tarball had no bin/catalina.sh at all
#      ("sudo: /opt/openmeetings/bin/catalina.sh: command not found" at
#      runtime).
#   3. A .dockerignore excluding docker/ -- fixed RAT, but the very next
#      build then flagged .dockerignore ITSELF as the one remaining
#      unapproved file (it doesn't exclude itself from COPY), and once that
#      was fixed too, excluding docker/ from the whole context broke the
#      runtime stage's own COPY of docker/scripts/*.sh (see above) -- three
#      cascading bugs from one mechanism, replaced with the simpler
#      per-stage `rm` below.
RUN rm -rf docker
# -PallModules matches this project's own documented build command (README.md).
# The full test suite can still be run separately (mvn test) when actually
# validating a patch, not on every rebuild.
#
# Cache-mount the local Maven repo -- without it, every rebuild (including one
# that only changes a single Java file to test a patch) re-downloads this
# multi-module project's entire dependency tree from scratch, which is both
# slow and needlessly wasteful of bandwidth on every iteration.
RUN --mount=type=cache,target=/root/.m2 mvn -B clean install -PallModules -DskipTests

# ---- Runtime stage (verbatim structure from openmeetings/openmeetings-docker@9.1.1) ----
FROM ubuntu:24.04
ENV OM_VER_MAJ='9'
ENV OM_VER_MIN='1'
ENV OM_VER_MIC='0'
ENV OM_VERSION="${OM_VER_MAJ}.${OM_VER_MIN}.${OM_VER_MIC}"
LABEL vendor="Tutorship (patched Apache OpenMeetings, whiteboard-recording branch)"
LABEL version="${OM_VERSION}-wbrec"
LABEL maintainer=dev@tutorship.biz

ENV DB_ROOT_PASS='12345'
ENV OM_USER="om_admin"
ENV OM_PASS="1Q2w3e4r5t^y"
ENV DAEMON_USER="nobody"
ENV DAEMON_UID="65534"
ENV OM_DB_NAME="openmeetings"
ENV OM_DB_TYPE="mysql"
ENV OM_DB_HOST="localhost"
ENV OM_DB_PORT="3306"
ENV OM_DB_USER="om_admin"
ENV OM_DB_PASS="12345"
ENV OM_KURENTO_WS_URL="ws://127.0.0.1:8888/kurento"
ENV TURN_URL=""
ENV TURN_USER=""
ENV TURN_PASS=""
ENV OM_DATA_DIR="/opt/omdata"
ENV work=/opt
ENV OM_HOME=/opt/openmeetings
ENV MYSQL_J_VER="26.7.0"
ENV DB2_J_VER="12.1.5.0"
ENV PORTS=5443
ENV SERVER_TZ=UTC

WORKDIR ${OM_HOME}
RUN apt-get update && apt-get install -y --no-install-recommends \
    apt-utils \
  && apt-get install -y --no-install-recommends \
    software-properties-common \
    gnupg2 \
    dirmngr \
    unzip \
    wget \
    ghostscript \
    libgs-dev \
    imagemagick \
    sox \
    sudo \
    openjdk-21-jre \
    libreoffice \
    ffmpeg \
  && apt-get clean \
  && rm -rf /var/lib/apt/lists/* \
  && sed -i 's|<policy domain="coder" rights="none" pattern="PS" />|<!--policy domain="coder" rights="none" pattern="PS" />|g; s|<policy domain="coder" rights="none" pattern="XPS" />|<policy domain="coder" rights="none" pattern="XPS" /-->|g' /etc/ImageMagick-6/policy.xml

# Our own build's tarball, not a downloaded release -- this is the one line that
# actually differs from the official Dockerfile's source-acquisition step.
COPY --from=builder /build/openmeetings-server/target/apache-openmeetings-${OM_VERSION}.tar.gz ${work}/om.tar.gz
RUN tar -xzf ${work}/om.tar.gz --strip-components=1 -C ${OM_HOME}/ \
  && rm -rf ${work}/om.tar.gz \
  && wget https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/${MYSQL_J_VER}/mysql-connector-j-${MYSQL_J_VER}.jar -P ${OM_HOME}/webapps/openmeetings/WEB-INF/lib \
  && wget https://repo1.maven.org/maven2/com/ibm/db2/jcc/${DB2_J_VER}/jcc-${DB2_J_VER}.jar -P ${OM_HOME}/webapps/openmeetings/WEB-INF/lib

WORKDIR ${work}
COPY docker/scripts/*.sh ./

RUN chmod a+x ${work}/*.sh

ARG BUILD_TYPE="min"
ENV OM_TYPE=${BUILD_TYPE}

RUN cat /etc/issue \
  && echo "OM server of type ${OM_TYPE} will be built" \
  && ./om_install.sh

EXPOSE ${PORTS}

ENTRYPOINT [ "bash", "-c", "${work}/om.sh" ]
