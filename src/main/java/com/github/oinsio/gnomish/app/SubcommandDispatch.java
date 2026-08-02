package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.DoNotMutate;
import java.io.IOException;
import org.springframework.boot.ApplicationArguments;

/**
 * Routes {@link ManualRunRunner#run} to {@link StatusCommand}, {@link UsageCommand}, {@link
 * TakeCommand}, or {@link ServeCommand} when the invocation's subcommand (design {@link
 * Subcommand#parse}) is {@code status}/{@code usage}/{@code take}/{@code serve} (FR13, FR14 of
 * add-git-workflow; FR9 of add-tracker-port; FR2 of add-factory-serve); a {@code run} subcommand —
 * explicit or implicit — is left for {@link ManualRunRunner}'s own flow. {@code take}/{@code
 * serve} are dispatched here rather than treated as {@code run} variants (unlike how {@code
 * status}/{@code usage} always were): each has an entirely separate flag set and must never fall
 * into {@link RunArgumentsParser}. Split out of {@link ManualRunRunner} purely to keep that class
 * within the project's file-size target (`.claude/rules/process-invariants.md`).
 *
 * <p>Unlike {@link TakeCommand#run}, {@link ServeCommand#run} does not always throw: a real {@code
 * serve} invocation either fails startup ({@code ServeExitCodeException}) or blocks in the feed
 * loop until interrupted, but a test substituting a non-blocking {@code FeedAutomatonStarter}
 * (task 5.1's seam) can make it return normally — {@link #dispatchNonRun} reports {@code true}
 * either way, so a normal return is not mistaken for the unhandled {@code run} subcommand.
 *
 * <p>Implements FR13, FR14 of add-git-workflow; FR9 of add-tracker-port; FR2 of add-factory-serve.
 */
record SubcommandDispatch(
        StatusCommand statusCommand, UsageCommand usageCommand, TakeCommand takeCommand, ServeCommand serveCommand) {

    /**
     * @param args the raw application arguments, as Spring Boot parsed them
     * @return {@code true} if {@code status}, {@code usage}, {@code take}, or {@code serve}
     *     handled the invocation (the caller must not also drive the run flow); {@code false} for
     *     the {@code run} subcommand
     * @throws UsageException if the first positional token names no known subcommand
     * @throws IOException if {@code take}'s or {@code serve}'s pipeline load fails with a genuine
     *     I/O fault
     * @throws InterruptedException if a {@code serve} invocation's feed loop is interrupted
     */
    boolean dispatchNonRun(ApplicationArguments args) throws IOException, InterruptedException {
        Subcommand subcommand = Subcommand.parse(args);
        if (subcommand == Subcommand.STATUS) {
            statusCommand.run(args);
            return true;
        }
        if (subcommand == Subcommand.USAGE) {
            usageCommand.run(args);
            return true;
        }
        if (subcommand == Subcommand.TAKE) {
            takeCommand.run(args);
        }
        if (subcommand == Subcommand.SERVE) {
            // Unlike TAKE (which never returns — see below), a test's non-blocking
            // FeedAutomatonStarter seam lets this call return normally, so this `return true` is
            // genuinely reachable and PIT-covered without an exemption.
            serveCommand.run(args);
            return true;
        }
        return isTake(subcommand);
    }

    // PIT M4 documented exception (build.gradle has the full rationale style): @DoNotMutate — this
    // final `return` in dispatchNonRun is provably unreachable-to-differ-in-practice for the TAKE
    // case: TakeCommand#run's own Javadoc documents "@throws TakeExitCodeException always, on a
    // completed run", so control never actually falls through from the `if (subcommand ==
    // Subcommand.TAKE)` block above to this statement for a real TAKE invocation — the negated
    // form (`!=`) would be indistinguishable from the correct one for TAKE and RUN alike, since
    // both currently-reachable cases (a non-TAKE, non-SERVE, non-STATUS, non-USAGE subcommand —
    // i.e. RUN) return `false` either way (`isTake(RUN)` is false; `!isTake(RUN)` would also be
    // reached only if TAKE returned normally, which it cannot). TAKE routing itself (that
    // takeCommand.run(args) is actually called) is proven by SubcommandDispatchSpec's "routes to
    // TakeCommand" scenario via TakeCommand's own distinct failure mode; the RUN case's `false`
    // result is covered for real by the "invokes neither command and returns false" scenario.
    @DoNotMutate
    private static boolean isTake(Subcommand subcommand) {
        return subcommand == Subcommand.TAKE;
    }
}
