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
# The reactor-wide install below skips tests (-DskipTests): upstream's own
# module test suites are heavy/DB-bound and are NOT run on every rebuild. The
# fork's OWN tests (the ones guarding this fork's patches, all in
# openmeetings-service) are run by the dedicated "test gate" step right after
# the install -- see below.
#
# Cache-mount the local Maven repo -- without it, every rebuild (including one
# that only changes a single Java file to test a patch) re-downloads this
# multi-module project's entire dependency tree from scratch, which is both
# slow and needlessly wasteful of bandwidth on every iteration.
# -Dmaven.javadoc.skip=true: -PallModules's attach-javadocs binding tries to
# cross-link against https://cxf.apache.org/javadoc/... at build time, which
# this sandboxed build environment has no route to (UnknownHostException,
# not transient -- retrying doesn't help). Irrelevant to a running server
# either way -- javadoc jars are a doc-distribution artifact, never loaded
# at runtime, and -DskipTests already means no test coverage is being
# traded away here.
RUN --mount=type=cache,target=/root/.m2 mvn -B clean install -PallModules -DskipTests -Dmaven.javadoc.skip=true

# ---- Test gate: the fork's own tests must pass or the image is not built ----
# Before this step existed the image build ran with -DskipTests only, so the
# fork's regression tests (TestSingleStreamConversionSubmitter pins CONV-1,
# the mkdirs() check-then-act race; TestWbRecordingManager pins the whiteboard
# asset export) protected nothing unless someone happened to run Maven by hand.
#
# What it runs: the plain `test` phase of openmeetings-service -- no -Dtest
# name list on purpose. The root pom's surefire config runs exactly the
# classes named Test*, so this is "every test the project's own convention
# says should run in that module", and a new Test* class is gated automatically
# instead of silently falling out of a hard-coded list. (Do NOT name a test
# class *Test: it will never run -- that is how WbRecordingManagerTest went
# unrun for weeks.)
#
# -DfailIfNoTests=true: surefire 3.5.x otherwise exits 0 / BUILD SUCCESS when
# it finds ZERO tests (verified by running it), so a module whose test classes
# were all deleted or renamed away from Test* would "pass" while protecting
# nothing. With the flag the same run fails with "No tests were executed!".
#
# The per-class pin after mvn: that flag only guards against zero tests in the
# whole module. With two fork test classes, renaming just ONE of them back to
# *Test would still leave the other running, so BUILD SUCCESS -- verified: this
# is exactly how WbRecordingManagerTest sat unrun. So the loop below also
# demands a surefire report for each named class. (Reports can only come from
# this step: the install above runs with -DskipTests, which writes none.) When
# you add a fork test class, add its simple name to this list.
#
# Executed-and-not-skipped, not just "a report exists": surefire also writes a
# report for a class whose tests were all SKIPPED -- a class-level @Disabled, or
# an @EnabledOnOs / Assumption that does not hold in the builder's Linux-as-root
# environment -- and the sibling class keeps the module-wide count above zero,
# so neither -DfailIfNoTests nor the existence check would notice: the image
# would build with CONV-1's regression test protecting nothing. So the loop also
# reads the tests="N" and skipped="M" attributes from each pinned class's own
# <testsuite> start tag and requires N >= 1 and M == 0. POSIX sh + tr + grep +
# head + sed only: the builder image (maven:3-eclipse-temurin-21, Ubuntu, /bin/sh
# = dash) has no python. The counts are read fail-CLOSED: an attribute that
# cannot be parsed fails the build rather than passing it. (Failures and errors
# need no check here -- they already make mvn itself exit non-zero.)
#
# The tag read is the FIRST "<testsuite " in the report -- the root element, which
# surefire writes before any test output. NOT the last one: the report embeds each
# test's stdout/stderr in a CDATA block, so a pinned test class that ever prints
# text containing its own "<testsuite ... tests=N skipped=0>" (a logged XML
# fragment, a report it parsed) would otherwise be read as if it were the root
# tag and could mask a real skipped="4" (verified: the old greedy-.* extraction
# printed "9 tests executed, 0 skipped" and exited 0 on exactly that input).
# tr first joins the lines so a root tag wrapped over several lines still parses.
#
# DEPENDENCY ON THE CACHE-MOUNTED ~/.m2 -- deliberately no -am. `-pl
# openmeetings-service` compiles only that one module and resolves its sibling
# modules (openmeetings-util, -db, -core, ...) as jars from ~/.m2. Those must be
# THIS FORK's jars, which only the install step above puts there (the cache
# mount is a separate store from the layer cache). What a pruned cache looks
# like -- verified by running this gate against a copy of ~/.m2 with the
# openmeetings-* entries deleted -- is a LOUD, early failure, never a false
# green: if everything is gone Maven stops at "Could not resolve dependencies
# for project ...openmeetings-service ... openmeetings-core:jar:9.1.0" (upstream
# never published that module to Central); if only util/db are gone Maven fetches
# the UPSTREAM 9.1.0 jars of those two from Central, which lack the fork-only
# symbols, and the build stops with COMPILE errors in openmeetings-service's own
# main sources -- "cannot find symbol ... markWorldReadable ... location: class
# OmFileHelper", plus an "incompatible types" error from the ProcessHelper.exec
# overload the fork added. Either way the fix is to rebuild the install step
# above (docker build --no-cache) -- NOT to reach for SKIP_FORK_TESTS=1.
#
# ~10 tests, well under a minute once the surefire provider is cached.
#
# EMERGENCY OFF-SWITCH: --build-arg SKIP_FORK_TESTS=1 (build_and_push.sh
# forwards the SKIP_FORK_TESTS env var, and tags such an image "-notests").
# ONLY the exact value 1 skips the gate; empty, 0, false, or anything else
# leaves it ON, so a mistyped "SKIP_FORK_TESTS=false" can never silently
# disable it. Declared HERE, not at the top, so that toggling it invalidates
# only this step's cache and never the expensive install above.
ARG SKIP_FORK_TESTS=""
RUN --mount=type=cache,target=/root/.m2 \
    if [ "${SKIP_FORK_TESTS}" = "1" ]; then \
        echo "WARNING: SKIP_FORK_TESTS=1 -- the fork's own tests were NOT run for this image"; \
    else \
        mvn -B -pl openmeetings-service test -DfailIfNoTests=true \
        && for t in TestSingleStreamConversionSubmitter TestWbRecordingManager; do \
            rep=$(ls openmeetings-service/target/surefire-reports/TEST-*."${t}".xml 2>/dev/null | head -n 1); \
            [ -n "${rep}" ] \
            || { echo "ERROR: test gate: ${t} did not run (class renamed away from Test*, or moved out of openmeetings-service?)"; exit 1; }; \
            suite=$(tr '\n' ' ' < "${rep}" | grep -o '<testsuite [^>]*>' | head -n 1); \
            tests=$(printf '%s' "${suite}" | sed -n 's/.* tests="\([0-9][0-9]*\)".*/\1/p'); \
            skipped=$(printf '%s' "${suite}" | sed -n 's/.* skipped="\([0-9][0-9]*\)".*/\1/p'); \
            { [ -n "${tests}" ] && [ -n "${skipped}" ]; } \
            || { echo "ERROR: test gate: could not read the tests/skipped counts of ${t} from ${rep} (surefire report format changed?)"; exit 1; }; \
            [ "${tests}" -ge 1 ] \
            || { echo "ERROR: test gate: ${t} executed no tests (tests=${tests} in ${rep})"; exit 1; }; \
            [ "${skipped}" -eq 0 ] \
            || { echo "ERROR: test gate: ${t} had ${skipped} of ${tests} tests SKIPPED (class- or method-level @Disabled, or an assumption that does not hold in this build environment?) -- a skipped test protects nothing"; exit 1; }; \
            echo "test gate: ${t}: ${tests} tests executed, ${skipped} skipped"; \
        done; \
    fi

# ---- Runtime stage (verbatim structure from openmeetings/openmeetings-docker@9.1.1) ----
FROM ubuntu:24.04
ENV OM_VER_MAJ='9'
ENV OM_VER_MIN='1'
ENV OM_VER_MIC='0'
ENV OM_VERSION="${OM_VER_MAJ}.${OM_VER_MIN}.${OM_VER_MIC}"
LABEL vendor="Tutorship (patched Apache OpenMeetings, full-recorder-replacement branch: single-stream + whiteboard recording combined)"
LABEL version="${OM_VERSION}-fullreplace"
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
