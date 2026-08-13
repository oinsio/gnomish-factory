package com.github.oinsio.gnomish.adapter.environment

/**
 * Builds (once per JVM) the JDK sandbox image the "Gradle build flows through
 * the guard" scenario (FR7, UX4) runs against — the reference image recipe's
 * JVM egress plumbing (docs/examples/sandbox-image/Dockerfile) reduced to its
 * essence: a JDK, the baked {@code JAVA_TOOL_OPTIONS} proxy system properties
 * pointed at the guard's stable network alias, and a tiny {@code Probe.java}
 * HTTP client run via the single-file source launcher.
 *
 * <p>Two things make this image a faithful stand-in for a Gradle build:
 * <ul>
 *   <li>the proxy address is carried as JVM <em>system properties</em>
 *       ({@code -Dhttp.proxyHost}/{@code -Dhttps.proxyHost}), the same channel
 *       Gradle honors and the reference image bakes into {@code GRADLE_OPTS};
 *   <li>it deliberately sets <em>no</em> {@code HTTP_PROXY} environment variable,
 *       so a request that reaches the guard proves the system property routed it
 *       — the JVM ignores the env-var spelling, which is the whole point of the
 *       requirement.
 * </ul>
 *
 * <p>The base is {@code eclipse-temurin:21-jdk-noble} (a real JDK, ~450 MB) —
 * the same family the reference recipe defaults to; the image builds on demand
 * and the spec is Docker-gated, so an offline machine skips it cleanly.
 */
class JvmProxyEgressImage {

    static final String IMAGE = 'gnomish-sandbox-jvm-proxy-test:latest'

    private static volatile boolean built = false

    /** Builds the image if this JVM has not yet; returns the tag. Asserts the build succeeds. */
    static synchronized String ensureBuilt() {
        if (built) {
            return IMAGE
        }
        def alias = GuardCommands.PROXY_ALIAS
        def port = GuardCommands.PROXY_PORT
        def dockerfile = """
            FROM eclipse-temurin:21-jdk-noble
            # Baked JVM proxy system properties — the reference image's plumbing.
            # NO HTTP_PROXY env var: a routed request proves the system property.
            ENV JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=${alias} -Dhttp.proxyPort=${port} -Dhttps.proxyHost=${alias} -Dhttps.proxyPort=${port}"
            # git is the container adapter's image contract — the seed clone runs it in-box.
            RUN apt-get update \\
             && apt-get install -y --no-install-recommends git \\
             && rm -rf /var/lib/apt/lists/*
            RUN mkdir -p /gnomish-probe \\
             && printf '%s\\n' \\
                'import java.net.HttpURLConnection;' \\
                'import java.net.URI;' \\
                'public class Probe {' \\
                '  public static void main(String[] a) throws Exception {' \\
                '    var c = (HttpURLConnection) URI.create(a[0]).toURL().openConnection();' \\
                '    c.setConnectTimeout(10000); c.setReadTimeout(10000);' \\
                '    System.out.println("HTTP " + c.getResponseCode());' \\
                '  }' \\
                '}' \\
                > /gnomish-probe/Probe.java
            # The in-box task user the container adapter execs as (uid 1000, owns
            # the file-channel roots); temurin noble ships a uid-1000 user, renamed.
            RUN if getent passwd 1000 >/dev/null; then \\
                  old="\$(getent passwd 1000 | cut -d: -f1)"; \\
                  usermod -l gnome -d /home/gnome -m "\$old"; \\
                  groupmod -n gnome "\$(getent group "\$(id -g gnome)" | cut -d: -f1)"; \\
                else \\
                  useradd -m -u 1000 gnome; \\
                fi \\
             && mkdir -p /gnomish/work /gnomish/scratch \\
             && chown -R gnome:gnome /gnomish
            USER gnome
            WORKDIR /gnomish/work
        """.stripIndent()
        DockerImageBuilder.build(IMAGE, dockerfile)
        built = true
        IMAGE
    }
}
