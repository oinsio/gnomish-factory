package com.github.oinsio.gnomish.app.port.git;

/**
 * Which namespace a resolved base ref lives in — the fact that decides where its refresh fetch
 * lands (design D11, D11a of add-base-ref-resolution; {@code docs/adr/0006-base-refresh-fetch.md}).
 *
 * <p>A base ref carries no kind of its own: the allowed bases and the task's designator name
 * {@code develop} or {@code v2.3.0}, and only the remote knows which namespace holds it. So the kind is an
 * <em>answer</em>, established by the adapter's remote-ref read before any fetch runs — never a declaration
 * a project writes down, which could only ever drift from what origin actually holds.
 *
 * <p>Implements FR6 of add-base-ref-resolution.
 */
public enum BaseRefKind {

    /** A branch: refreshes into its remote-tracking ref, forced — the clone's cache of origin. */
    BRANCH,

    /** A tag: written into {@code refs/tags/} without force, exactly as git's own auto-follow. */
    TAG,

    /** A bare commit: it has no tip to refresh, so the only question is whether the clone holds it. */
    COMMIT
}
