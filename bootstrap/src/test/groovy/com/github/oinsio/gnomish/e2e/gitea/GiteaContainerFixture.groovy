package com.github.oinsio.gnomish.e2e.gitea

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait

/**
 * Real HTTP-auth git remote for the Gitea E2E layer (task 6.5, FR11, G2 infra): starts a plain
 * {@code gitea/gitea} container pre-installed via env vars (SQLite, install lock on — no browser
 * setup wizard), bootstraps one admin user via {@code gitea admin user create} and one API access
 * token, then creates one empty repository the caller can push to over
 * {@code http://<user>:<token>@host:port/<user>/<repo>.git}.
 *
 * <p>Deliberately a plain {@link GenericContainer} rather than a dedicated Testcontainers module:
 * none is published for Gitea, and the bootstrap sequence (env-var install + one {@code docker
 * exec} + two REST calls) is simple enough not to need one.
 *
 * <p>Callers own the container lifecycle explicitly ({@link #start()}/{@link #stop()}) from a
 * spec's {@code setupSpec}/{@code cleanupSpec} — Gitea startup is slow enough that one container
 * per spec class, not per test method, is the intended usage (matches the shared-fixture style of
 * {@link com.github.oinsio.gnomish.e2e.E2eProcessHarness}). Every caller must first check
 * {@link GiteaAvailability#dockerAvailable()} and skip (e.g. via Spock {@code @IgnoreIf}) rather
 * than construct this fixture when Docker is absent.
 *
 * <p>The container is shared, the repositories in it are not, so: {@link #authenticatedCloneUrl()}
 * names the one bootstrapped repo, for a remote wired ONCE from {@code setupSpec} and read by
 * every feature after; {@link #createRepository(String)} is for a remote wired per feature (from
 * {@code setup} or a feature body) — features that each push their own root commit into one repo
 * turn the second feature's push into a non-fast-forward rejection.
 *
 * <p>The {@code actionsEnabled} constructor (task 7.1, M1) additionally sets {@code
 * GITEA__actions__ENABLED=true} and attaches the container to a shared Testcontainers {@link
 * Network} under the fixed alias {@link #NETWORK_ALIAS}, so a sibling {@code act_runner} container
 * (see {@code GiteaActionsRunnerFixture}) can reach it by container-to-container hostname rather
 * than the host-mapped port. The plain no-arg constructor is unchanged — no network, Actions off —
 * so the existing plain-Gitea E2E specs keep their exact prior behavior.
 *
 * <p>Implements FR11 of add-git-workflow (G2 infra); the {@code actionsEnabled} path implements M1
 * of add-external-check-github-actions.
 */
class GiteaContainerFixture {

    static final String ADMIN_USER = 'gnomish-e2e'
    static final String ADMIN_PASSWORD = 'gnomish-e2e-password'
    static final String ADMIN_EMAIL = 'gnomish-e2e@example.invalid'
    static final String REPO_NAME = 'gnomish-e2e-repo'

    /** Container-network hostname alias other containers on the same {@link #network()} use to reach this one. */
    static final String NETWORK_ALIAS = 'gitea'

    private static final String IMAGE = 'gitea/gitea:1.27.0'
    private static final int HTTP_PORT = 3000

    private final GenericContainer<?> container
    private final Network network

    private String token

    GiteaContainerFixture() {
        this(false)
    }

    /**
     * @param actionsEnabled when {@code true}, enables Gitea Actions ({@code
     *     GITEA__actions__ENABLED=true}) and attaches the container to a fresh {@link Network}
     *     under {@link #NETWORK_ALIAS}, for a sibling {@code act_runner} container to join
     *     (task 7.1, M1 of add-external-check-github-actions)
     */
    GiteaContainerFixture(boolean actionsEnabled) {
        network = actionsEnabled ? Network.newNetwork() : null
        container = new GenericContainer<>(IMAGE)
                .withExposedPorts(HTTP_PORT)
                .withEnv('GITEA__security__INSTALL_LOCK', 'true')
                .withEnv('GITEA__database__DB_TYPE', 'sqlite3')
                .withEnv('USER_UID', '1000')
                .withEnv('USER_GID', '1000')
                .waitingFor(Wait.forHttp('/').forStatusCode(200))
        if (actionsEnabled) {
            container.withEnv('GITEA__actions__ENABLED', 'true')
                    .withNetwork(network)
                    .withNetworkAliases(NETWORK_ALIAS)
        }
    }

    /** @return the shared network this container joined, or {@code null} when built with the plain constructor */
    Network network() {
        network
    }

    /**
     * @return this container's base API URL reachable from a sibling container on {@link
     *     #network()} (container-to-container, not the host-mapped port), e.g. {@code
     *     http://gitea:3000}
     */
    String internalUrl() {
        "http://${NETWORK_ALIAS}:${HTTP_PORT}"
    }

    /** Starts the container, then bootstraps the admin user, token, and empty repository. */
    void start() {
        container.start()
        createAdminUser()
        token = createAccessToken()
        createRepo(REPO_NAME)
    }

    /** Stops and removes the container. Safe to call even if {@link #start()} was never called. */
    void stop() {
        container.stop()
    }

    /** @return the shared repo's clone URL with the admin token embedded, usable as a git {@code origin}. */
    String authenticatedCloneUrl() {
        cloneUrlFor(REPO_NAME)
    }

    /**
     * Creates one more empty repository and returns its authenticated clone URL — the per-feature
     * remote of the sharing rule above. {@code name} must be unique per caller; suffix it with
     * {@code System.nanoTime()} or the task id.
     */
    String createRepository(String name) {
        createRepo(name)
        cloneUrlFor(name)
    }

    /** @return the container's base API URL, e.g. {@code http://localhost:<port>/api/v1} */
    String apiBaseUrl() {
        "http://${container.host}:${container.getMappedPort(HTTP_PORT)}/api/v1"
    }

    /** @return the bootstrapped admin's API access token, for direct REST calls beyond git push/pull */
    String adminToken() {
        token
    }

    /**
     * Generates a one-shot global Actions runner registration token via the {@code gitea} CLI
     * (same {@code docker exec} pattern as {@link #createAdminUser()}), for a sibling {@code
     * act_runner} container to self-register with on startup.
     *
     * <p>Implements M1 of add-external-check-github-actions.
     *
     * @return the registration token, trimmed of any trailing newline
     */
    String createRunnerRegistrationToken() {
        def result = container.execInContainerWithUser('git', 'gitea', 'actions', 'generate-runner-token')
        assert result.exitCode == 0: "gitea actions generate-runner-token failed: ${result.stderr}"
        result.stdout.trim()
    }

    private void createAdminUser() {
        // The gitea binary refuses to run as root (Gitea's own safety check); the official image
        // runs its web server as the unprivileged `git` user, and the same user owns the app data
        // this CLI subcommand writes into — `docker exec` defaults to root, so the user must be
        // pinned explicitly.
        def result = container.execInContainerWithUser(
                'git',
                'gitea', 'admin', 'user', 'create',
                '--username', ADMIN_USER,
                '--password', ADMIN_PASSWORD,
                '--email', ADMIN_EMAIL,
                '--admin',
                '--must-change-password=false')
        assert result.exitCode == 0: "gitea admin user create failed: ${result.stderr}"
    }

    private String createAccessToken() {
        String body = '{"name":"e2e","scopes":["write:repository","write:user"]}'
        String basicAuth = Base64.encoder.encodeToString("${ADMIN_USER}:${ADMIN_PASSWORD}".bytes)
        HttpResponse<String> response = post(
                "${apiBaseUrl()}/users/${ADMIN_USER}/tokens", body, "Basic ${basicAuth}")
        assert response.statusCode() == 201: "token creation failed: ${response.body()}"
        (response.body() =~ /"sha1":"([a-f0-9]+)"/)[0][1]
    }

    private String cloneUrlFor(String repo) {
        "http://${ADMIN_USER}:${token}@${container.host}:${container.getMappedPort(HTTP_PORT)}/${ADMIN_USER}/${repo}.git"
    }

    private void createRepo(String name) {
        String body = "{\"name\":\"${name}\",\"private\":false,\"auto_init\":false}"
        HttpResponse<String> response = post("${apiBaseUrl()}/user/repos", body, "token ${token}")
        assert response.statusCode() == 201: "repo creation failed: ${response.body()}"
    }

    private static HttpResponse<String> post(String url, String jsonBody, String authHeader) {
        HttpClient client = HttpClient.newHttpClient()
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header('Content-Type', 'application/json')
                .header('Authorization', authHeader)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build()
        client.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
