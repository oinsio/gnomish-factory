package com.github.oinsio.gnomish.gittransfer;

import java.util.ArrayList;
import java.util.List;

/**
 * The factory's two pushes to {@code origin}, as {@link GitTransfer} values — the only
 * construction sites of a push argv in the factory (design D13 of tighten-spec-contract):
 *
 * <ul>
 *   <li>{@link #closing} — the closing push: the task branch and the task's {@link RegisterRef} in
 *       one {@code git push --atomic}, so origin's branch and register land together or not at
 *       all. One durable step, no window between them (crash-consistency item 9). Every factory
 *       closing commit — STARTED, a round's state commit, an outcome, an approval, a decision, a
 *       salvage, the cleanup — and every catch-up of one is delivered by this value.
 *   <li>{@link #branchOnly} — a mid-round push: the branch alone, the register left where the
 *       last closing push put it.
 * </ul>
 *
 * <p>Both open with the factory's invocation policy ({@link GitInvocation#configPairs()}), so a
 * clone's {@code core.hooksPath} runs no {@code pre-push} hook (FR4, design D2 of
 * own-git-invocation-policy). Both name each ref in full on both sides of its refspec ({@code refs/heads/<b>:refs/heads/<b>}),
 * so nothing rides on git's refspec inference or on {@code push.default}; neither forces, so a
 * non-fast-forward is a refused push. Neither follows tags nor recurses into submodules, whatever
 * the operator's {@code push.followTags} or {@code submodule.recurse} say, and {@code
 * --end-of-options} precedes the remote. The environment is origin's fetch environment ({@link
 * TransferSource.Origin}): inherited per-process configuration stripped, the protocol allowlist
 * set, the operator's configuration kept — it is how the operator authenticates.
 *
 * <p>Implements FR13, NFR-S2 of tighten-spec-contract; FR4 of own-git-invocation-policy.
 */
public final class GitPush {

    private GitPush() {}

    /**
     * The closing push of {@code branch} together with {@code register}, atomically. Each ref is
     * pushed from the clone's own ref of the same name, so origin receives exactly what the clone
     * records.
     *
     * @param branch the task branch name, e.g. {@code gnomish/T-1}; never blank
     * @param register the task's register; never null
     * @return the transfer value
     */
    public static GitTransfer closing(String branch, RegisterRef register) {
        List<String> argv = start("--atomic");
        argv.add(same("refs/heads/" + requireBranch(branch)));
        argv.add(same(register.name()));
        return new GitTransfer(argv, GitTransfer.environment(TransferSource.ORIGIN));
    }

    /**
     * The mid-round push of {@code branch} alone.
     *
     * @param branch the task branch name; never blank
     * @return the transfer value
     */
    public static GitTransfer branchOnly(String branch) {
        List<String> argv = start();
        argv.add(same("refs/heads/" + requireBranch(branch)));
        return new GitTransfer(argv, GitTransfer.environment(TransferSource.ORIGIN));
    }

    /** The invocation policy's pairs, then the subcommand and the flags common to both pushes. */
    private static List<String> start(String... flags) {
        List<String> argv = new ArrayList<>(GitInvocation.configPairs());
        argv.add("push");
        argv.addAll(List.of(flags));
        argv.add("--no-follow-tags");
        argv.add("--no-recurse-submodules");
        argv.add(GitTransfer.END_OF_OPTIONS);
        argv.add("origin");
        return argv;
    }

    /** {@code ref:ref}, through the refspec value, which refuses an empty or option-like one. */
    private static String same(String ref) {
        return new Refspec(ref + ":" + ref).value();
    }

    private static String requireBranch(String branch) {
        if (branch.isBlank() || branch.contains(":")) {
            throw new IllegalArgumentException("a pushed branch is one non-blank name with no ':': " + branch);
        }
        return branch;
    }
}
