package com.github.oinsio.gnomish.buildlogic

import groovy.transform.Immutable
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import org.gradle.process.ExecSpec

/**
 * The daemon endpoint of the operator's current Docker context, read by one
 * {@code docker context inspect} (design D14 of fix-terminal-receipt-convergence, item 3;
 * implements FR14, NFR-C3 of fix-terminal-receipt-convergence). {@link TestDockerEnvironment}
 * obtains it only when the operator set no {@code DOCKER_HOST}, and once per build.
 *
 * <p>{@code docker context inspect} is the CLI's documented interface to its context store: it
 * calls no daemon and no credential helper. It runs under the operator's own configuration — the
 * operator's {@code DOCKER_CONFIG} and {@code DOCKER_CONTEXT} are parameters, so a change to either
 * invalidates the configuration cache, and are set on the process explicitly rather than trusted to
 * reach it through the daemon's inherited environment. The client's {@code PATH} is a parameter for
 * the same reason: {@link ClientPathTool} finds {@code docker} on it and the inspect runs that
 * absolute path, never a bare name the JDK would resolve on the {@code PATH} the daemon started with
 * (D14 as amended 2026-10-18, task 7.5).
 *
 * <p>A {@code ValueSource} for the same reason as {@link HardwareSpec}: under the configuration
 * cache Gradle re-obtains it on every build and reuses the stored configuration only when the
 * answer is the same — one {@code docker} process per build either way.
 *
 * <p>No {@code docker} binary, a failing inspect or a blank answer: {@link Endpoint#NONE}, so the
 * build sets no endpoint and the Docker specs skip through their own availability checks; a
 * {@code docker} the client's {@code PATH} does not hold says so in a build warning.
 */
abstract class DockerContextEndpoint implements ValueSource<Endpoint, Parameters> {

    /** The Go template asked of the CLI: the endpoint, then {@code tls} when the context carries TLS material. */
    static final String FORMAT = '{{.Endpoints.docker.Host}}|{{if .TLSMaterial}}tls{{end}}'

    interface Parameters extends ValueSourceParameters {

        /** The {@code docker} to run: the bare {@code docker}, found on {@link #getClientPath}, or a path a spec chooses. */
        Property<String> getExecutable()

        /** The client's {@code PATH}, absent when unset: where a bare {@link #getExecutable} is looked up. */
        Property<String> getClientPath()

        /** The operator's {@code DOCKER_CONFIG}, absent when unset. */
        Property<String> getOperatorConfig()

        /** The operator's {@code DOCKER_CONTEXT}, absent when unset. */
        Property<String> getOperatorContext()
    }

    /** What the context names: a host, or none, and whether the context holds TLS material. */
    @Immutable
    static class Endpoint implements Serializable {

        static final Endpoint NONE = new Endpoint(null, false)

        /** The endpoint, {@code null} for none. */
        String host
        boolean tls
    }

    @Inject
    abstract ExecOperations getExecOperations()

    /**
     * Through the injected {@link ExecOperations}, as {@link MutationScopeSource} runs git: Gradle's
     * own process launcher, with no timeout of its own — the inspect reads the CLI's context store
     * and calls no daemon, so nothing in it waits on the network.
     */
    @Override
    Endpoint obtain() {
        String docker = ClientPathTool.locate(parameters.executable.get(), parameters.clientPath.getOrNull(),
                'the test JVMs get no DOCKER_HOST, and the Docker specs skip')
        if (docker == null) {
            return Endpoint.NONE
        }
        def out = new ByteArrayOutputStream()
        try {
            def result = execOperations.exec { spec ->
                spec.commandLine(docker, 'context', 'inspect', '--format', FORMAT)
                operatorVariable(spec, 'DOCKER_CONFIG', parameters.operatorConfig.getOrNull())
                operatorVariable(spec, 'DOCKER_CONTEXT', parameters.operatorContext.getOrNull())
                spec.standardOutput = out
                spec.errorOutput = OutputStream.nullOutputStream()
                spec.ignoreExitValue = true
            }
            result.exitValue == 0 ? parse(out.toString(StandardCharsets.UTF_8).trim()) : Endpoint.NONE
        } catch (GradleException ignored) {
            // The resolved docker could not be started: the Docker specs skip through their own
            // checks. Narrower than RuntimeException, so a defect here fails loudly.
            Endpoint.NONE
        }
    }

    /** {@code <host>|<tls or nothing>}, as {@link #FORMAT} prints it; a blank host is no endpoint. */
    static Endpoint parse(String answer) {
        int bar = answer.lastIndexOf('|')
        String host = (bar < 0 ? answer : answer.substring(0, bar)).trim()
        boolean tls = bar >= 0 && answer.substring(bar + 1).trim() == 'tls'
        host.isEmpty() ? Endpoint.NONE : new Endpoint(host, tls)
    }

    /** Not private: Groovy resolves it from the exec closure inside Gradle's generated subclass. */
    static void operatorVariable(ExecSpec spec, String name, String value) {
        if (value == null) {
            spec.environment.remove(name)
        } else {
            spec.environment(name, value)
        }
    }
}
