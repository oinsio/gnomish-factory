package com.github.oinsio.gnomish.adapter.tracker.github;

/**
 * Splits the {@code tracker.github.repo} subsection value (a single {@code "owner/repo"}
 * string, already schema-validated as present by {@link GithubTrackerSubsectionValidator}) into
 * its owner and repo halves. Shared by every caller that needs the split — {@link
 * GithubRefExpander} (short-ref expansion, FR9) and {@link GithubTrackerAdapterFactory} (adapter
 * construction, task 5.15) — so the split logic exists exactly once.
 *
 * <p>Implements FR9, FR17 of add-tracker-port.
 *
 * @param owner the repository owner
 * @param repo the repository name
 */
public record GithubRepoRef(String owner, String repo) {

    /**
     * Parses {@code "owner/repo"} into its two halves.
     *
     * @param repoValue the raw {@code tracker.github.repo} value; never null
     * @return the split owner/repo pair
     * @throws IllegalArgumentException if {@code repoValue} is not a well-formed {@code
     *     "owner/repo"} string
     */
    public static GithubRepoRef parse(String repoValue) {
        int slash = repoValue.indexOf('/');
        if (slash <= 0 || slash == repoValue.length() - 1) {
            throw new IllegalArgumentException("tracker.github.repo must be 'owner/repo', but was '" + repoValue + "'");
        }
        return new GithubRepoRef(repoValue.substring(0, slash), repoValue.substring(slash + 1));
    }
}
