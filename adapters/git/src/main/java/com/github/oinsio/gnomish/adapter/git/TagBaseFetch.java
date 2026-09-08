package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.BaseRefKind;
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The tag half of the base refresh: {@code refs/tags/<n>:refs/tags/<n>}, deliberately <em>without
 * force</em>, plus the report a refusal produces.
 *
 * <p>Tags have no remote-tracking namespace, and an ordinary fetch auto-follows them into {@code
 * refs/tags/} anyway, so this destination is where git itself would put them. Omitting the force is
 * the whole policy: git's own semantics are create-if-absent and refuse-to-move, so a local tag of
 * the same name pointing elsewhere stops the refresh instead of being overwritten. That refusal is
 * the one real way the factory could touch a ref the operator owns, and the alternative — force —
 * was rejected for exactly that reason ({@code docs/adr/0006-base-refresh-fetch.md}).
 *
 * <p>A refusal is a task-level park, not an outage: the operator's clone and origin disagree about
 * what a version tag means, which is a fact about the repository that no retry resolves. The report
 * names both commits so the human can see which one they meant.
 *
 * <p>Implements FR6, FR9 of add-base-ref-resolution.
 */
final class TagBaseFetch {

    private final GitProcessRunner runner;

    TagBaseFetch(GitProcessRunner runner) {
        this.runner = runner;
    }

    /**
     * Brings tag {@code name} into {@code refs/tags/} and reads back the commit it points at.
     *
     * @param cloneDir the factory clone; never null
     * @param name the tag name, unqualified
     * @param originCommit what origin's refs read said {@code refs/tags/<name>} points at, used to
     *     tell a divergence apart from a fetch that simply did not answer
     * @return the refreshed commit, a refusal naming both commits, or the infrastructure arm
     */
    BaseRefreshOutcome fetch(Path cloneDir, String name, String originCommit) {
        String ref = "refs/tags/" + name;
        Optional<String> before = localTagObject(cloneDir, ref);
        // The divergence is decidable before the fetch runs, and deciding it here rather than from
        // git's refusal message keeps the answer independent of git's locale and wording.
        if (before.isPresent() && !before.get().equals(originCommit)) {
            return new BaseRefreshOutcome.Refused(divergenceReport(name, before.get(), originCommit));
        }
        GitCommandResult fetch = NarrowFetch.of(runner, cloneDir, ref + ":" + ref);
        return RefreshedTip.of(runner, cloneDir, fetch, ref, name, BaseRefKind.TAG);
    }

    private static String divergenceReport(String name, String local, String origin) {
        return "The base tag '" + name + "' means different commits here and on origin, so the refresh "
                + "refused to move it: this clone's tag points at " + local + ", origin's at " + origin
                + ". Tags are fetched without force, so the operator's own tag is never overwritten. "
                + "Delete or re-point the local tag, or name a base that does not collide.";
    }

    /** What the local tag ref itself points at — the tag object for an annotated tag, unpeeled. */
    private Optional<String> localTagObject(Path cloneDir, String ref) {
        return VerifiedTip.read(runner.run(cloneDir, "rev-parse", "--verify", "--quiet", ref));
    }
}
