package com.github.oinsio.gnomish.buildlogic

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.ProcessForkOptions

/**
 * The one place the test build composes the Docker client environment of a forked test JVM
 * (design D14 of fix-terminal-receipt-convergence; implements FR13, FR14, FR15, NFR-C3, UX4 of
 * fix-terminal-receipt-convergence): {@code test-docker-environment-conventions}, applied by
 * {@code test-conventions} beside {@link AdversarialGitConfig}, hands it every {@code Test} task
 * and the {@code pitest} task, whose minions inherit its environment.
 *
 * <p>The failure this exists for: every test JVM and every PIT minion read the operator's
 * {@code ~/.docker/config.json}, so Testcontainers and the {@code docker} CLI ran its credential
 * helper — on macOS {@code docker-credential-desktop}, which raises a system prompt and blocked a
 * minion for over 20 s (task 6.9) — and the CLI applied the operator's plugin hooks and proxies to
 * what the tests built and ran. Four variables, on the task's environment:
 *
 * <ul>
 *   <li>{@code DOCKER_CONFIG} names a copy of the committed {@link #RELATIVE_PATH}, made in the
 *       task's temporary directory before it runs ({@code buildx} writes into the configuration
 *       directory, so the committed file is never handed out itself). The copy's
 *       {@code cliPluginsExtraDirs} names the operator's {@code cli-plugins} directory when it
 *       exists, where Docker Desktop links {@code buildx} on macOS.
 *   <li>{@code DOCKER_HOST} is the operator's own when set; otherwise the endpoint of the
 *       operator's current context ({@link DockerContextEndpoint}, one {@code docker} process per
 *       build, found on the client's {@code PATH}). With it set, the CLI and Testcontainers talk to one daemon by construction.
 *   <li>{@code DOCKER_CONTEXT} is removed: the clean configuration holds no contexts.
 *   <li>{@code TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE} is {@code /var/run/docker.sock} on a macOS
 *       host with a {@code unix://} endpoint, unless the operator set it: Testcontainers mounts the
 *       socket path of {@code DOCKER_HOST} into Ryuk, and on macOS that host path does not exist
 *       inside the daemon's Linux VM.
 * </ul>
 *
 * <p>An endpoint that one variable cannot carry — an {@code ssh://} host, a context with TLS
 * material — fails the build with the variables to set instead. Only the committed configuration
 * is a task input, by content: the endpoint, the override and the plugin directory differ per
 * machine and change nothing a test asserts, so a cache entry is shared between macOS and Linux.
 *
 * <p>Applied at configuration time, after {@link TestEnvironmentHygiene}'s reasoning: a task whose
 * environment was modified while it was configured has its whole map stored by the configuration
 * cache, and the variables this class reads come through {@code providers.environmentVariable},
 * so a change to any of them invalidates the stored map instead of being ignored by it.
 *
 * <p>Kept in sync with {@code scripts/linux-specs.sh} (the Linux spec runner, design D9 of
 * make-pipe-reads-non-parking), which calls {@code docker} outside Gradle and cannot load this
 * class: both hand the CLI a copy of {@link #RELATIVE_PATH} whose {@code cliPluginsExtraDirs}
 * names the operator's {@code cli-plugins} directory when it exists, and both take
 * {@code DOCKER_HOST} from the operator or from the current context's endpoint (the
 * {@link DockerContextEndpoint#FORMAT} query), refusing an {@code ssh://} or TLS endpoint, and
 * remove {@code DOCKER_CONTEXT}.
 */
final class TestDockerEnvironment {

    /** Relative to the root project — the one committed client configuration. */
    static final String RELATIVE_PATH = 'test-fixtures/src/main/resources/docker-config/config.json'

    static final String CONFIG = 'DOCKER_CONFIG'
    static final String HOST = 'DOCKER_HOST'
    static final String CONTEXT = 'DOCKER_CONTEXT'
    static final String SOCKET_OVERRIDE = 'TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE'

    /** The socket path every macOS Docker runtime provides inside its VM. */
    static final String VM_SOCKET = '/var/run/docker.sock'

    /** Where the endpoint is kept once obtained, so one build runs at most one {@code docker} process. */
    private static final String RESOLVED = 'gnomish.testDockerEndpoint'

    private TestDockerEnvironment() {
    }

    /** Composes the four variables for {@code task} on this machine. */
    static <T extends Task & ProcessForkOptions> void applyTo(Project project, T task) {
        applyTo(project, task, new Host(System.getProperty('os.name'), 'docker'))
    }

    /**
     * Composes the four variables for {@code task} on {@code host} — the host's operating system and
     * the {@code docker} it runs, which a spec chooses. {@code T} is a {@code Test} or the
     * {@code pitest} task — anything that forks a process.
     */
    static <T extends Task & ProcessForkOptions> void applyTo(Project project, T task, Host host) {
        String operatorHost = operator(project, HOST)
        DockerContextEndpoint.Endpoint endpoint = operatorHost != null
                ? new DockerContextEndpoint.Endpoint(operatorHost, false)
                : resolved(project, host)
        if (operatorHost == null && endpoint.host != null) {
            refuseUnless(endpoint)
            task.environment(HOST, endpoint.host)
        }
        task.environment.remove(CONTEXT)
        if (host.macOs && endpoint.host?.startsWith('unix://') && operator(project, SOCKET_OVERRIDE) == null) {
            task.environment(SOCKET_OVERRIDE, VM_SOCKET)
        }
        File committed = project.rootProject.file(RELATIVE_PATH)
        File copy = new File(task.temporaryDir, 'docker-config')
        task.environment(CONFIG, copy.absolutePath)
        task.inputs.file(committed)
                .withPropertyName('dockerClientConfig')
                .withPathSensitivity(PathSensitivity.NONE)
        task.doFirst('copy the Docker client configuration',
                new ConfigCopy(committed, copy, operatorPlugins(project)))
    }

    /** The operating system and the {@code docker} of the machine the build composes for. */
    static final class Host {

        final String osName
        final String dockerExecutable

        Host(String osName, String dockerExecutable) {
            this.osName = osName
            this.dockerExecutable = dockerExecutable
        }

        boolean isMacOs() {
            osName.toLowerCase(Locale.ROOT).startsWith('mac')
        }
    }

    /** Writes the copy: the committed configuration, plus the operator's plugin directory when it exists. */
    static final class ConfigCopy implements Action<Task>, Serializable {

        private final File committed
        private final File copy
        private final File operatorPlugins

        ConfigCopy(File committed, File copy, File operatorPlugins) {
            this.committed = committed
            this.copy = copy
            this.operatorPlugins = operatorPlugins
        }

        @Override
        void execute(Task task) {
            copy.deleteDir()
            copy.mkdirs()
            Map<String, Object> config = new JsonSlurper().parse(committed) as Map<String, Object>
            if (operatorPlugins?.isDirectory()) {
                config.cliPluginsExtraDirs = [operatorPlugins.absolutePath]
            }
            new File(copy, 'config.json').text = JsonOutput.prettyPrint(JsonOutput.toJson(config)) + '\n'
        }
    }

    private static DockerContextEndpoint.Endpoint resolved(Project project, Host host) {
        def extra = project.gradle.extensions.extraProperties
        if (!extra.has(RESOLVED)) {
            extra.set(RESOLVED, project.providers.of(DockerContextEndpoint) {
                it.parameters.executable = host.dockerExecutable
                it.parameters.clientPath = project.providers.environmentVariable('PATH')
                it.parameters.operatorConfig = project.providers.environmentVariable(CONFIG)
                it.parameters.operatorContext = project.providers.environmentVariable(CONTEXT)
            }.get())
        }
        extra.get(RESOLVED) as DockerContextEndpoint.Endpoint
    }

    private static void refuseUnless(DockerContextEndpoint.Endpoint endpoint) {
        if (endpoint.host.startsWith('ssh://') || endpoint.tls) {
            throw new GradleException("The current Docker context's endpoint (${endpoint.host}"
                    + "${endpoint.tls ? ', with TLS material' : ''}) cannot be handed to the test JVMs as one "
                    + 'variable. Set DOCKER_HOST in the environment that launches the build — and, for a TLS '
                    + 'endpoint, DOCKER_CERT_PATH and DOCKER_TLS_VERIFY; all three reach the tests unchanged.')
        }
    }

    /**
     * The operator's configuration directory: their {@code DOCKER_CONFIG}, else {@code $HOME/.docker} —
     * the CLI's own default, which it finds through {@code HOME}, not through the JVM's idea of home.
     * {@code null} when neither is set: there is then no operator directory to find plugins in.
     */
    private static File operatorConfigDir(Project project) {
        String dir = operator(project, CONFIG)
        String home = operator(project, 'HOME')
        dir != null ? new File(dir) : home != null ? new File(home, '.docker') : null
    }

    private static File operatorPlugins(Project project) {
        File dir = operatorConfigDir(project)
        dir == null ? null : new File(dir, 'cli-plugins')
    }

    /** An operator variable, blank as absent. */
    private static String operator(Project project, String name) {
        String value = project.providers.environmentVariable(name).getOrNull()
        value == null || value.isBlank() ? null : value
    }
}
