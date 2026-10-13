package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.RoundToken;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.subprocess.Termination;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The one inspection of a range of gnome commits before it may enter the task branch (design D18
 * of tighten-spec-contract): {@code base..top} — the base an ancestor of the top, no commit with a
 * second parent, no commit whose subject is in the {@code gnomish: } namespace, no commit changing a
 * path under the state directory its {@link PathPermit} does not name. Where the range ends in the
 * factory's own in-box closing commit ({@link AdmissionRule.FactoryTop}), that one commit is judged
 * by its own rule: its subject must be the factory's, its paths the ones the factory wrote.
 *
 * <p>Three outcomes ({@link RangeVerdict}): a violation names the commit; a git invocation that did
 * not run to a zero exit is cannot-verify — the round aborts as infrastructure, no attempt is burned
 * and nothing is attributed to the gnome.
 *
 * <p>It replaces the declared pair of the host round-boundary check and the harvested-ref check
 * (deleted by task 9.13c), preference 1 of {@code manual-sync-pairs.md}: one rule, two callers — the harvest
 * admission ({@link HarvestAdmission}) over {@code register..quarantine}, and the host round close
 * ({@link GitAttemptPersistence}) over {@code previousTip..HEAD}.
 *
 * <p>Implements FR13, NFR-S2 of tighten-spec-contract; FR21, FR23 of add-sandbox-core; FR12 of
 * add-git-workflow; FR13 of harden-logging-observability; FR13, FR16 of make-checkpoint-gate-durable.
 */
@UntrustedParser
final class GnomeRangeInspection {

    /** The subject namespace of every factory service commit ({@link ServiceCommitMessages}). */
    static final String SERVICE_SUBJECT = "gnomish: ";

    private static final String FIELD = "\u001f";

    private static final String RECORD = "\u001e";

    private final GitRunner runner;
    private final Path repo;

    /**
     * @param runner the git subprocess seam
     * @param repo the clone, or a worktree of it, holding both ends of the range
     */
    GnomeRangeInspection(GitRunner runner, Path repo) {
        this.runner = runner;
        this.repo = repo;
    }

    /**
     * Judges {@code base..top} under {@code rule}.
     *
     * @param base the commit the range starts above — the register, or the host's previous tip
     * @param top the commit the range ends at — the quarantined harvest, or {@code HEAD}
     * @param rule what the range may contain
     * @return clean, the first violation found, or cannot-verify
     */
    RangeVerdict inspect(String base, String top, AdmissionRule rule) {
        GitCommandResult ancestry = runner.run(repo, "merge-base", "--is-ancestor", base, top);
        if (ancestry.termination() == Termination.EXITED && ancestry.exitCode() == 1) {
            return violated("the previous tip " + base + " is not an ancestor of " + top + " (history rewrite)");
        }
        if (!ancestry.succeeded()) {
            return new RangeVerdict.CannotVerify(ancestry.cannotVerifyDetail());
        }
        GitCommandResult commits =
                runner.run(repo, "log", "--no-color", "--format=%H" + FIELD + "%P" + FIELD + "%s", base + ".." + top);
        if (!commits.succeeded()) {
            return new RangeVerdict.CannotVerify(commits.cannotVerifyDetail());
        }
        // @UntrustedParser warrant (design D11 of type-untrusted-text): each line becomes a verdict —
        //     the object ids are compared, the subject is matched against the factory's own — and the
        //     subject a violation quotes goes back into a carrier, leaving through the log exit.
        for (String line : commits.stdout().forParsing().lines().toList()) {
            String[] fields = line.split(FIELD, 3);
            RangeVerdict verdict = judgeCommit(fields[0], fields[1], fields[2], top, rule);
            if (!(verdict instanceof RangeVerdict.Clean)) {
                return verdict;
            }
        }
        return judgePaths(base, top, rule);
    }

    private static RangeVerdict judgeCommit(
            String commit, String parents, String subject, String top, AdmissionRule rule) {
        if (parents.contains(" ")) {
            return violated("commit " + commit + " has a second parent (history was merged in)");
        }
        boolean factoryTop = commit.equals(top) && rule.top().isPresent();
        if (factoryTop && !subject.equals(rule.top().orElseThrow().subject())) {
            return violated("the closing commit " + commit + " is not the factory's: its subject is "
                    + UntrustedText.subprocess(subject).forLog());
        }
        if (!factoryTop && subject.startsWith(SERVICE_SUBJECT)) {
            return violated("commit " + commit + " carries a service subject the factory did not write: "
                    + UntrustedText.subprocess(subject).forLog());
        }
        return new RangeVerdict.Clean();
    }

    private RangeVerdict judgePaths(String base, String top, AdmissionRule rule) {
        GitCommandResult touched = runner.run(
                repo,
                "log",
                "--no-color",
                "--no-renames",
                "--format=" + RECORD + "%H",
                "--name-only",
                base + ".." + top,
                "--",
                EnvelopePaths.DIR_NAME);
        if (!touched.succeeded()) {
            return new RangeVerdict.CannotVerify(touched.cannotVerifyDetail());
        }
        List<String> refused = new ArrayList<>();
        // @UntrustedParser warrant (design D11): the paths are compared with the permitted ones; the
        //     refused ones go back into a carrier and leave through the log exit.
        for (String entry : touched.stdout().forParsing().split(RECORD, -1)) {
            List<String> lines =
                    entry.strip().lines().filter(line -> !line.isBlank()).toList();
            if (lines.isEmpty()) {
                continue;
            }
            String commit = lines.getFirst();
            PathPermit permit = commit.equals(top) && rule.top().isPresent()
                    ? rule.top().orElseThrow().permit()
                    : rule.gnome();
            lines.stream()
                    .skip(1)
                    .filter(path -> !permit.permits(path))
                    .forEach(path -> refused.add(commit + " " + path));
        }
        if (refused.isEmpty()) {
            return new RangeVerdict.Clean();
        }
        return violated(EnvelopePaths.DIR + " was modified by the gnome: "
                + UntrustedText.subprocess(String.join(", ", refused)).forLog());
    }

    /**
     * The single gnome-writable path under {@code .gnomish-task/} for one round (FR23 of
     * add-sandbox-core; FR13, FR16 of make-checkpoint-gate-durable): {@code
     * .gnomish-task/decisions/<stage>-a<attempt>-<token>.json} — the one spelling of the decision
     * path, used by {@link BranchDecisionFile}'s handle and by the admission's carve-out. There is
     * no token-less form: a stage and attempt repeat across visits of a stage, so only the round
     * token names the round that owns the file.
     */
    static String decisionPath(AttemptKey key, RoundToken token) {
        return EnvelopePaths.DECISIONS_DIR + "/" + key.stage() + "-a" + key.attempt() + "-" + token.commit() + ".json";
    }

    private static RangeVerdict violated(String sentence) {
        // Factory prose quoting captures that left their carriers through the log exit (design D3
        // of type-untrusted-text), so the violation receives the sentence whole.
        return new RangeVerdict.Violated(UntrustedText.factory(sentence));
    }
}
