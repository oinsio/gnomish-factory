package com.github.oinsio.gnomish.adapter.scm;

import java.util.Map;

/**
 * The repository a built-in's operator connection names, {@code repo: owner/name} (FR4, NFR-S2 of
 * migrate-verify-to-executor-contract): the one reading of the key and the one statement of its
 * shape, shared by the {@code github-actions} and {@code scm-status} built-ins, and by the load-time
 * validator of the transitional {@code github} provider.
 *
 * <p>Implements FR4, NFR-S2 of migrate-verify-to-executor-contract.
 */
public final class ScmRepository {

    /** The connection key naming the repository. */
    public static final String KEY = "repo";

    private final String owner;
    private final String name;

    private ScmRepository(String owner, String name) {
        this.owner = owner;
        this.name = name;
    }

    /**
     * Reads the repository from a resolved connection subsection.
     *
     * @param where the subsection's key, {@code factory.check.<name>}; never blank
     * @param settings the resolved subsection; never null
     * @return the repository; never null
     * @throws IllegalArgumentException when the key is absent or blank, or is not {@code owner/name}
     */
    public static ScmRepository of(String where, Map<String, Object> settings) {
        if (!(settings.get(KEY) instanceof String repo) || repo.isBlank()) {
            throw new IllegalArgumentException(where + "." + KEY + " is required");
        }
        if (!isOwnerName(repo)) {
            throw new IllegalArgumentException(where + "." + KEY + " must be 'owner/name', got: '" + repo + "'");
        }
        int slash = repo.indexOf('/');
        return new ScmRepository(repo.substring(0, slash), repo.substring(slash + 1));
    }

    /**
     * Whether {@code repo} holds exactly one non-empty segment on either side of a single {@code /}.
     *
     * @param repo the configured value; never null
     * @return true for {@code owner/name}
     */
    public static boolean isOwnerName(String repo) {
        int slash = repo.indexOf('/');
        return slash > 0 && slash == repo.lastIndexOf('/') && slash < repo.length() - 1;
    }

    /** The repository's owner. */
    public String owner() {
        return owner;
    }

    /** The repository's name. */
    public String name() {
        return name;
    }

    /** The {@code /repos/<owner>/<name>} path every repository call of the platform's API starts with. */
    public String apiPath() {
        return "/repos/" + owner + "/" + name;
    }
}
