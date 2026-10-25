# The JVM egress image of JvmProxyEgressE2ESpec (scenarios sandbox/sandbox-egress "Gradle build
# flows through the guard" and "Plumbing is inherited, not copied"): the sandbox base image plus a
# JDK and Maven, grafted from the official Maven image in a stage of their own (design D13), and a
# single-file HTTP probe. The JVM's proxy comes from the base's JAVA_TOOL_OPTIONS (the same system
# properties the base puts in GRADLE_OPTS), Maven's from the base's root-owned settings; this file
# sets no proxy of its own, and the JVM ignores the base's HTTP_PROXY spelling, so a request that
# reaches the guard was routed by the inherited plumbing. Built by SandboxBaseImage.buildFrom with
# BASE = the base fixture's tag.
# Implements FR10 of add-sandbox-base-image (design D7).
ARG BASE
FROM maven:3-eclipse-temurin-25 AS maven

FROM ${BASE}
USER root
COPY --from=maven /opt/java/openjdk /opt/java/openjdk
COPY --from=maven /usr/share/maven /usr/share/maven
ENV JAVA_HOME=/opt/java/openjdk \
    MAVEN_HOME=/usr/share/maven \
    PATH=/usr/share/maven/bin:/opt/java/openjdk/bin:${PATH}
# No --chmod: BuildKit would give the created /gnomish-probe directory the same mode, without x.
COPY Probe.java /gnomish-probe/Probe.java
USER gnome
