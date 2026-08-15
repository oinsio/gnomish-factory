package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import java.util.Map;

/**
 * Expands a recognized GitHub short ref (a bare issue number, e.g. {@code 42} from {@code 42} or
 * {@code #42}) into the canonical {@link TaskRef} for the project's configured {@code
 * tracker.github} binding (FR9, design D4, D7 of add-tracker-port): splits the subsection's {@code
 * repo} key (already schema-validated as a single {@code "owner/repo"} string by {@link
 * GithubTrackerSubsectionValidator}) into owner/repo, then delegates to {@link
 * GithubTaskId#build(String, String, String, int)} for the actual canonical-id construction
 * (including the api-url default-vs-non-default host decision, design D7).
 *
 * <p>This class only does the config-shaped half of expansion (subsection lookup, {@code repo}
 * splitting); recognizing that a raw ref string IS short in the first place is generic,
 * adapter-agnostic logic, done by the caller ({@code com.github.oinsio.gnomish.app.ShortRef})
 * before this class is ever invoked.
 *
 * <p>Implements FR9 of add-tracker-port.
 */
public final class GithubRefExpander {

    private GithubRefExpander() {}

    /**
     * Expands a GitHub short ref's issue number into a canonical {@link TaskRef}.
     *
     * @param subsection the project's validated {@code tracker.github} subsection, carrying at
     *     least {@code api-url} and {@code repo} (a single {@code "owner/repo"} string); never null
     * @param issueNumber the parsed issue number from the recognized short ref
     * @return the expanded, canonical {@link TaskRef}, e.g. {@code github:owner/repo#42}
     * @throws IllegalArgumentException if {@code repo} is missing or not a well-formed {@code
     *     "owner/repo"} string
     */
    public static TaskRef expand(Map<String, Object> subsection, int issueNumber) {
        String apiUrl = stringValue(subsection, "api-url");
        String repoValue = stringValue(subsection, "repo");
        GithubRepoRef repoRef = GithubRepoRef.parse(repoValue);
        GithubTaskId taskId = GithubTaskId.build(apiUrl, repoRef.owner(), repoRef.repo(), issueNumber);
        return new TaskRef(taskId.canonicalId());
    }

    private static String stringValue(Map<String, Object> subsection, String key) {
        Object value = subsection.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("tracker.github." + key + " is required to expand a short ref");
        }
        return s;
    }
}
