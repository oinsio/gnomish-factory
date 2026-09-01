package com.github.oinsio.gnomish.adapter.tracker.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cross-cutting precondition for FR9's explicit {@code take <ref>} matrix
 * (design D8): when a canonical id's {@code owner/repo} differs from the
 * configured binding, resolves the id's repo with one
 * {@code GET /repos/{owner}/{repo}} and reads the response body's
 * {@code full_name} field — GitHub resolves a renamed repo's redirect
 * server-side and returns 200 with the current owner/repo in {@code
 * full_name}, so no HTTP-level redirect following is needed. A {@code
 * full_name} matching the configured repo is a legitimate pre-rename id
 * (WARN, proceed); anything else — a genuinely different repo, or the id's
 * repo not existing at all (404) — is refused.
 *
 * <p>Not itself one of the ten {@link com.github.oinsio.gnomish.app.port.tracker.Tracker}
 * port operations. The take-runner wiring that calls this check lives in {@link
 * GithubTrackerAdapterFactory#refuseForeignRef(com.github.oinsio.gnomish.domain.pipeline.TrackerConfig,
 * com.github.oinsio.gnomish.app.port.tracker.TaskRef, String)}, which {@code
 * TakeCommand} invokes in explicit mode before {@code fetchTask}; this class
 * remains a standalone, independently testable unit.
 *
 * <p>Implements FR9, FR16 of add-tracker-port.
 */
// Not a record: this is a behavior-bearing precondition service (a collaborator holding an
// HTTP client, not immutable data), kept as a plain final class.
@SuppressWarnings("ClassCanBeRecord")
public final class GithubForeignRepoCheck {

    private static final Logger log = LoggerFactory.getLogger(GithubForeignRepoCheck.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GithubHttpClient httpClient;

    public GithubForeignRepoCheck(GithubHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Verifies {@code id}'s repo is either the configured repo or a
     * legitimate rename-redirect predecessor of it; throws otherwise.
     *
     * @param id the canonical task id under check
     * @param configuredOwner the configured {@code tracker.github.repo} owner
     * @param configuredRepo the configured {@code tracker.github.repo} name
     * @throws GithubForeignRepoException if {@code id}'s repo neither is nor
     *     redirects to the configured repo
     */
    public void verify(GithubTaskId id, String configuredOwner, String configuredRepo) {
        if (id.owner().equals(configuredOwner) && id.repo().equals(configuredRepo)) {
            return;
        }

        String idRepo = id.owner() + "/" + id.repo();
        String configuredFullName = configuredOwner + "/" + configuredRepo;
        Optional<String> fullName = resolveFullName(id);

        if (fullName.filter(configuredFullName::equals).isPresent()) {
            // FR12: a rename redirect that resolved is a recovered transient, not a degradation
            //     — the verification succeeded and nothing about the run is worse for it.
            log.info(
                    "Task id names {} which GitHub now reports as {} (rename redirect) — proceeding",
                    idRepo,
                    configuredFullName);
            return;
        }

        throw new GithubForeignRepoException(
                "Task id names repo %s but the factory is configured for %s".formatted(idRepo, configuredFullName));
    }

    /** Returns the resolved {@code full_name}, or empty if the id's repo does not exist (404). */
    private Optional<String> resolveFullName(GithubTaskId id) {
        String path = "/repos/%s/%s".formatted(id.owner(), id.repo());
        HttpRequest.Builder request = httpClient.newRequest(path).GET();
        HttpResponse<String> response = httpClient.send(request);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() / 100 != 2) {
            throw new GithubForeignRepoException(
                    "Failed to resolve repo %s/%s: HTTP %d".formatted(id.owner(), id.repo(), response.statusCode()));
        }
        return parseFullName(response.body(), id);
    }

    private static Optional<String> parseFullName(String body, GithubTaskId id) {
        try {
            JsonNode node = MAPPER.readTree(body);
            return Optional.ofNullable(node.path("full_name").asText(null));
        } catch (JsonProcessingException e) {
            throw new GithubForeignRepoException(
                    "Failed to parse repo response for %s/%s".formatted(id.owner(), id.repo()), e);
        }
    }
}
