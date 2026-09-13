package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.subprocess.Termination;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Asks {@code origin}, in one refs read, which namespace it holds a base name in — {@code git
 * ls-remote origin refs/heads/<n> refs/tags/<n>} (design D11a).
 *
 * <p>Classifying before fetching, rather than trying a branch refspec and reading absence out of a
 * failed fetch, is the rule {@link TaskBranchLocator} and {@link RemoteBranchTip} already carry:
 * only a remote that actually answered may put a ref's absence on the record. It also makes the
 * collision arm expressible at all — and that arm is the security half. Git resolves an unqualified
 * ambiguous name <em>to the tag</em>, silently; a factory inheriting that preference would let
 * whoever can push a tag redirect a task's base onto their own commit. So a name origin holds in
 * both namespaces is {@link Held.Both}, which parks the task naming both commits, and never a pick.
 *
 * <p>Implements FR6, FR9, NFR-P1 of add-base-ref-resolution.
 */
final class RemoteBaseRef {

    private static final String HEADS = "refs/heads/";
    private static final String TAGS = "refs/tags/";

    /** {@code <sha>\t<ref>} — {@code ls-remote}'s only output shape. */
    private static final Pattern REF_LINE = Pattern.compile("^(\\S+)\\s+(\\S+)$");

    private final GitProcessRunner runner;
    private final OriginRemote origin;

    RemoteBaseRef(GitProcessRunner runner) {
        this.runner = runner;
        this.origin = new OriginRemote(runner);
    }

    /** What one refs read established about where {@code origin} holds a base name. */
    sealed interface Held {

        /** Origin holds it only as a branch. */
        record Branch(String commit) implements Held {}

        /** Origin holds it only as a tag; {@code commit} is the tag object, not yet peeled. */
        record Tag(String commit) implements Held {}

        /**
         * Origin holds it as both, so the name names nothing on its own.
         *
         * @param branchCommit what {@code refs/heads/<n>} points at
         * @param tagCommit what {@code refs/tags/<n>} points at
         */
        record Both(String branchCommit, String tagCommit) implements Held {}

        /** Origin answered and holds the name in neither namespace — a fact, so never retried. */
        record Absent() implements Held {}

        /** The clone has no {@code origin} to ask: deterministic, and a misconfiguration. */
        record NoRemote() implements Held {}

        /** Origin never answered — the one arm the infrastructure budget is spent on. */
        record Unanswered(String reason) implements Held {}
    }

    /**
     * Reads which of the asked-for namespaces {@code origin} holds {@code name} in.
     *
     * <p>Asking for one namespace only is the shape a resume takes when the task's pin already
     * records which namespace origin held the name in (D7 of add-base-ref-resolution, revised
     * 2026-09-10). The collision arm is then structurally unreachable, which is the point: the
     * pinned kind IS the remote fact, established when the base was first resolved, so a name later
     * reused in the other namespace can neither redirect the task nor park it.
     *
     * @param cloneDir the factory clone the read runs from; never null
     * @param name a base ref name, neither {@code refs/}-qualified nor a pattern
     * @param branches true to look in {@code refs/heads/}
     * @param tags true to look in {@code refs/tags/}
     * @return the namespace answer, or why none could be established
     */
    Held read(Path cloneDir, String name, boolean branches, boolean tags) {
        GitCommandResult refs = branches && tags
                ? LsRemote.refs(runner, cloneDir, HEADS + name, TAGS + name)
                : LsRemote.refs(runner, cloneDir, (branches ? HEADS : TAGS) + name);
        if (refs.termination() != Termination.EXITED || refs.exitCode() != 0) {
            // A clone with no origin was never going to answer, and re-asking it spends an
            // infrastructure budget on a settled fact — the same split the default-branch read makes.
            if (!origin.isConfigured(cloneDir)) {
                return new Held.NoRemote();
            }
            return new Held.Unanswered(refs.failureDetail("base refs read"));
        }
        return classify(
                branches ? commitAt(refs.stdout(), HEADS + name).orElse(null) : null,
                tags ? commitAt(refs.stdout(), TAGS + name).orElse(null) : null);
    }

    private static Held classify(@Nullable String branch, @Nullable String tag) {
        if (branch != null && tag != null) {
            return new Held.Both(branch, tag);
        }
        if (branch != null) {
            return new Held.Branch(branch);
        }
        return tag != null ? new Held.Tag(tag) : new Held.Absent();
    }

    /**
     * The commit {@code ls-remote} listed for exactly {@code ref}. The equality is deliberate: the
     * peeled {@code refs/tags/<n>^{}} line an annotated tag can produce names a different object,
     * and taking it for the tag's own would report a commit the tag ref does not point at.
     */
    private static Optional<String> commitAt(String stdout, String ref) {
        return stdout.lines()
                .map(REF_LINE::matcher)
                .filter(line -> line.matches() && line.group(2).equals(ref))
                .map(line -> line.group(1))
                .findFirst();
    }
}
