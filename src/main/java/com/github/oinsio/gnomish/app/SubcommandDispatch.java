package com.github.oinsio.gnomish.app;

import org.springframework.boot.ApplicationArguments;

/**
 * Routes {@link ManualRunRunner#run} to {@link StatusCommand} or {@link UsageCommand} when the
 * invocation's subcommand (design {@link Subcommand#parse}) is {@code status}/{@code usage}
 * (FR13, FR14 of add-git-workflow); a {@code run} subcommand — explicit or implicit — is left for
 * {@link ManualRunRunner}'s own flow. Split out of {@link ManualRunRunner} purely to keep that
 * class within the project's file-size target (`.claude/rules/process-invariants.md`).
 *
 * <p>Implements FR13, FR14 of add-git-workflow.
 */
final class SubcommandDispatch {

    private final StatusCommand statusCommand;
    private final UsageCommand usageCommand;

    SubcommandDispatch(StatusCommand statusCommand, UsageCommand usageCommand) {
        this.statusCommand = statusCommand;
        this.usageCommand = usageCommand;
    }

    /**
     * @param args the raw application arguments, as Spring Boot parsed them
     * @return {@code true} if {@code status} or {@code usage} handled the invocation (the caller
     *     must not also drive the run flow); {@code false} for the {@code run} subcommand
     * @throws UsageException if the first positional token names no known subcommand
     */
    boolean dispatchNonRun(ApplicationArguments args) {
        Subcommand subcommand = Subcommand.parse(args);
        if (subcommand == Subcommand.STATUS) {
            statusCommand.run(args);
            return true;
        }
        if (subcommand == Subcommand.USAGE) {
            usageCommand.run(args);
            return true;
        }
        return false;
    }
}
