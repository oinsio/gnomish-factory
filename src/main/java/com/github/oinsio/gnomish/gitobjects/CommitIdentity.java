package com.github.oinsio.gnomish.gitobjects;

/**
 * The name/email pair git records as a commit's author or committer. Supplied by the caller (never
 * read from ambient git config) so commit ids stay deterministic for fixed inputs (design D19).
 *
 * <p>Implements FR25 of add-sandbox-core.
 */
public record CommitIdentity(String name, String email) {

    public CommitIdentity {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("commit identity name must not be blank");
        }
        if (email == null) {
            throw new IllegalArgumentException("commit identity email must not be null");
        }
    }
}
