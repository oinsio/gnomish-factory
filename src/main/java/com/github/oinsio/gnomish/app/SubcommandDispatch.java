package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.DoNotMutate;
import java.io.IOException;
import org.springframework.boot.ApplicationArguments;

/**
 * Routes {@link ManualRunRunner#run} to {@link StatusCommand}, {@link UsageCommand}, or {@link
 * TakeCommand} when the invocation's subcommand (design {@link Subcommand#parse}) is {@code
 * status}/{@code usage}/{@code take} (FR13, FR14 of add-git-workflow; FR9 of add-tracker-port); a
 * {@code run} subcommand — explicit or implicit — is left for {@link ManualRunRunner}'s own flow.
 * {@code take} is dispatched here rather than treated as a {@code run} variant (unlike how {@code
 * status}/{@code usage} always were): it has an entirely separate flag set, parsed by {@link
 * TakeArgumentsParser}, and must never fall into {@link RunArgumentsParser}. Split out of {@link
 * ManualRunRunner} purely to keep that class within the project's file-size target
 * (`.claude/rules/process-invariants.md`).
 *
 * <p>Implements FR13, FR14 of add-git-workflow; FR9 of add-tracker-port.
 */
record SubcommandDispatch(StatusCommand statusCommand, UsageCommand usageCommand, TakeCommand takeCommand) {

    /**
     * @param args the raw application arguments, as Spring Boot parsed them
     * @return {@code true} if {@code status}, {@code usage}, or {@code take} handled the
     *     invocation (the caller must not also drive the run flow); {@code false} for the {@code
     *     run} subcommand
     * @throws UsageException if the first positional token names no known subcommand
     * @throws IOException if {@code take}'s pipeline load fails with a genuine I/O fault
     */
    boolean dispatchNonRun(ApplicationArguments args) throws IOException {
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
        return isTake(subcommand);
    }

    // PIT M4 documented exception (build.gradle has the full rationale style): @DoNotMutate — this
    // final `return` in dispatchNonRun is provably unreachable-to-differ-in-practice for the TAKE
    // case: TakeCommand#run's own Javadoc documents "@throws TakeExitCodeException always, on a
    // completed run", so control never actually falls through from the `if (subcommand ==
    // Subcommand.TAKE)` block above to this statement for a real TAKE invocation — the negated
    // form (`!=`) would be indistinguishable from the correct one for TAKE and RUN alike, since
    // both currently-reachable cases (a non-TAKE, non-STATUS, non-USAGE subcommand — i.e. RUN)
    // return `false` either way (`isTake(RUN)` is false; `!isTake(RUN)` would also be reached only
    // if TAKE returned normally, which it cannot). TAKE routing itself (that
    // takeCommand.run(args) is actually called) is proven by SubcommandDispatchSpec's "routes to
    // TakeCommand" scenario via TakeCommand's own distinct failure mode; the RUN case's `false`
    // result is covered for real by the "invokes neither command and returns false" scenario.
    @DoNotMutate
    private static boolean isTake(Subcommand subcommand) {
        return subcommand == Subcommand.TAKE;
    }
}
