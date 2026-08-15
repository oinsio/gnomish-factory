package com.github.oinsio.gnomish.e2e.gitea

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait

/**
 * Testcontainers wrapper around a {@code gitea/act_runner} container (task 7.1, M1 of
 * add-external-check-github-actions): the Gitea server does not execute Actions workflows by
 * itself — a registered runner is required alongside it (proposal.md Q1 resolution). This fixture
 * joins the runner to the same {@link GiteaContainerFixture#network()} the Actions-enabled Gitea
 * container is on, so it can reach it by the {@link GiteaContainerFixture#internalUrl()}
 * container-to-container hostname. The runner's Docker socket is bind-mounted from the host so the
 * image's container-mode default stays viable, but this fixture runs the smoke job in host mode
 * (see below), so the socket is not on the smoke path — the job executes inside the runner itself.
 *
 * <p>The official {@code gitea/act_runner} image self-registers on startup from {@code
 * GITEA_INSTANCE_URL}/{@code GITEA_RUNNER_REGISTRATION_TOKEN} and then runs its poll daemon — no
 * explicit {@code register} step is issued by this fixture. The runner registers with a single
 * explicit {@code ubuntu-latest:host} label ({@code GITEA_RUNNER_LABELS}), so the {@code runs-on:
 * ubuntu-latest} smoke job executes in <em>host mode</em> — directly inside this runner container —
 * rather than in an ephemeral sibling job container. Host mode is what makes the smoke workflow
 * deterministic here: the step runs in the runner container, which is already on {@link
 * GiteaContainerFixture#network()} and can reach Gitea at {@link
 * GiteaContainerFixture#internalUrl()} to report the result back. This is deliberate and required.
 * The image's baked-in <em>default</em> config instead maps {@code
 * ubuntu-latest:docker://gitea/runner-images:ubuntu-latest} (container mode); a sibling job
 * container spawned via the host Docker socket would land on the host's default bridge, not this
 * Testcontainers network, and could not resolve the {@code gitea} alias to report back — the run
 * would hang until the poll times out. Env-var self-registration without a label config falls back
 * to <em>bare</em> labels ({@code ubuntu-latest} with no {@code schema://image}), which also happen
 * to run in host mode, but that fallback is undocumented; the explicit {@code :host} label pins the
 * behavior. Host mode needs no image pull, so the smoke run concludes in about a second (task 7.1
 * scope — a trivial {@code echo} step, no real Ubuntu toolchain required).
 *
 * <p>Caller owns the lifecycle explicitly ({@link #start()}/{@link #stop()}), started only after
 * the paired {@link GiteaContainerFixture} so the registration token it needs already exists.
 *
 * <p>Implements M1 of add-external-check-github-actions.
 */
class GiteaActionsRunnerFixture {

    private static final String IMAGE = 'gitea/act_runner:0.2.11'
    private static final String DOCKER_SOCKET = '/var/run/docker.sock'

    /**
     * Runs the {@code runs-on: ubuntu-latest} job in host mode (inside this runner container, on the
     * Testcontainers network) instead of an unreachable sibling job container — see the class doc.
     */
    private static final String HOST_LABEL = 'ubuntu-latest:host'

    private final GenericContainer<?> container

    /**
     * @param gitea the Actions-enabled Gitea fixture (built with {@code new
     *     GiteaContainerFixture(true)}) this runner registers against; must already be started
     * @param registrationToken a fresh token from {@link GiteaContainerFixture#createRunnerRegistrationToken()}
     */
    GiteaActionsRunnerFixture(GiteaContainerFixture gitea, String registrationToken) {
        container = new GenericContainer<>(IMAGE)
                .withNetwork(gitea.network())
                .withEnv('GITEA_INSTANCE_URL', gitea.internalUrl())
                .withEnv('GITEA_RUNNER_REGISTRATION_TOKEN', registrationToken)
                .withEnv('GITEA_RUNNER_NAME', 'gnomish-e2e-runner')
                .withEnv('GITEA_RUNNER_LABELS', HOST_LABEL)
                .withFileSystemBind(DOCKER_SOCKET, DOCKER_SOCKET)
                .waitingFor(Wait.forLogMessage('.*\\n', 1))
    }

    /** Starts the runner container; returns once at least one log line has been produced. */
    void start() {
        container.start()
    }

    /** Stops and removes the container. Safe to call even if {@link #start()} was never called. */
    void stop() {
        container.stop()
    }

    /** @return the runner container's captured stdout+stderr, for diagnosing a registration or job-pickup failure */
    String logs() {
        container.logs
    }
}
