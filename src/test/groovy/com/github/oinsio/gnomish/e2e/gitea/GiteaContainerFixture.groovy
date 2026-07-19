package com.github.oinsio.gnomish.e2e.gitea

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import org.testcontainers.containers.GenericContainer
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
 * <p>Implements FR11 of add-git-workflow (G2 infra).
 */
class GiteaContainerFixture {

    static final String ADMIN_USER = 'gnomish-e2e'
    static final String ADMIN_PASSWORD = 'gnomish-e2e-password'
    static final String ADMIN_EMAIL = 'gnomish-e2e@example.invalid'
    static final String REPO_NAME = 'gnomish-e2e-repo'

    private static final String IMAGE = 'gitea/gitea:1.27.0'
    private static final int HTTP_PORT = 3000

    private final GenericContainer<?> container

    private String token

    GiteaContainerFixture() {
        container = new GenericContainer<>(IMAGE)
                .withExposedPorts(HTTP_PORT)
                .withEnv('GITEA__security__INSTALL_LOCK', 'true')
                .withEnv('GITEA__database__DB_TYPE', 'sqlite3')
                .withEnv('USER_UID', '1000')
                .withEnv('USER_GID', '1000')
                .waitingFor(Wait.forHttp('/').forStatusCode(200))
    }

    /** Starts the container, then bootstraps the admin user, token, and empty repository. */
    void start() {
        container.start()
        createAdminUser()
        token = createAccessToken()
        createRepository()
    }

    /** Stops and removes the container. Safe to call even if {@link #start()} was never called. */
    void stop() {
        container.stop()
    }

    /**
     * @return the HTTP(S) clone URL with the bootstrapped admin token embedded, e.g. {@code
     *     http://gnomish-e2e:<token>@localhost:<port>/gnomish-e2e/gnomish-e2e-repo.git} — ready to
     *     use directly as a git {@code origin}
     */
    String authenticatedCloneUrl() {
        "http://${ADMIN_USER}:${token}@${container.host}:${container.getMappedPort(HTTP_PORT)}/${ADMIN_USER}/${REPO_NAME}.git"
    }

    /** @return the container's base API URL, e.g. {@code http://localhost:<port>/api/v1} */
    String apiBaseUrl() {
        "http://${container.host}:${container.getMappedPort(HTTP_PORT)}/api/v1"
    }

    /** @return the bootstrapped admin's API access token, for direct REST calls beyond git push/pull */
    String adminToken() {
        token
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

    private void createRepository() {
        String body = "{\"name\":\"${REPO_NAME}\",\"private\":false,\"auto_init\":false}"
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
