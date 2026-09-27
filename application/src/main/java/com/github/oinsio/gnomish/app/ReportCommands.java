package com.github.oinsio.gnomish.app;

import java.io.IOException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * The read-only report subcommands — {@code status}, {@code usage}, {@code board} and {@code
 * dashboard} — as one facade that routes an invocation to whichever of them it names (design D2
 * of collapse-composition-roots). What the four share is what they do not do: none claims a task,
 * writes to the tracker or drives the pipeline, so they are dispatched as one group ahead of
 * {@code take} and {@code serve}, which {@link SubcommandDispatch} still routes itself.
 *
 * <p>Implements FR1, FR4 of collapse-composition-roots; FR13, FR14 of add-git-workflow; FR1 of
 * add-board-command; FR1 of add-dashboard-page.
 */
@Component
final class ReportCommands {

    private final StatusCommand statusCommand;
    private final UsageCommand usageCommand;
    private final BoardCommand boardCommand;
    private final DashboardCommand dashboardCommand;

    ReportCommands(
            StatusCommand statusCommand,
            UsageCommand usageCommand,
            BoardCommand boardCommand,
            DashboardCommand dashboardCommand) {
        this.statusCommand = statusCommand;
        this.usageCommand = usageCommand;
        this.boardCommand = boardCommand;
        this.dashboardCommand = dashboardCommand;
    }

    /**
     * Runs the report {@code subcommand} names, if it names one.
     *
     * @param subcommand the invocation's parsed subcommand
     * @param args the raw application arguments, including the leading subcommand token
     * @return {@code true} if a report command handled the invocation; {@code false} for any
     *     subcommand that is not a report, which this facade leaves untouched
     * @throws IOException if {@code board}'s or {@code dashboard}'s pipeline load fails with a
     *     genuine I/O fault
     */
    boolean run(Subcommand subcommand, ApplicationArguments args) throws IOException {
        switch (subcommand) {
            case STATUS -> statusCommand.run(args);
            case USAGE -> usageCommand.run(args);
            case BOARD -> boardCommand.run(args);
            case DASHBOARD -> dashboardCommand.run(args);
            default -> {
                return false;
            }
        }
        return true;
    }
}
