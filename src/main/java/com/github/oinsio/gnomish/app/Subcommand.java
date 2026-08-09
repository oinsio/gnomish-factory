package com.github.oinsio.gnomish.app;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.ApplicationArguments;

/**
 * The entrypoint's top-level dispatch: {@code gnomish run} | {@code status} | {@code usage} |
 * {@code take} | {@code serve} | {@code board} | {@code dashboard} (FR13, FR14 of add-git-workflow;
 * FR9 of add-tracker-port; FR2 of add-factory-serve; FR1 of add-board-command; FR1 of
 * add-dashboard-page). The subcommand is the
 * first raw source argument that is not a Spring Boot option (does not start with {@code --}) —
 * {@link ApplicationArguments} has no built-in concept of a leading positional subcommand, so this
 * reads {@link ApplicationArguments#getSourceArgs()} directly, the same raw-args seam {@link
 * InteractiveModeParser} already uses for a similar reason.
 *
 * <p>Absent entirely, {@link #RUN} is implicit: this preserves the flag-only invocation ({@code
 * gnomish --dir=... --task=...}) that predates subcommand dispatch, so existing scripts and specs
 * built against zero-or-flags-only invocations keep working unchanged. An explicit {@code run}
 * token is equivalent. Any other first positional token is a usage error, mapped by {@link
 * RunExitCodeMapper} to exit code 2 like every other {@link UsageException}.
 *
 * <p>Implements FR13, FR14 of add-git-workflow; FR9 of add-tracker-port.
 */
enum Subcommand {
    /** {@code gnomish run ...} or no subcommand at all — the pre-existing manual-run flow. */
    RUN,
    /** {@code gnomish status --dir <dir> [<task>] [--json]} — read-only task inspection. */
    STATUS,
    /** {@code gnomish usage --dir <dir> <task> [--json]} — read-only per-round usage report. */
    USAGE,
    /** {@code gnomish take [<ref>]} — single-task tracker mode, its own flag set entirely. */
    TAKE,
    /** {@code gnomish serve [--dir] [--slots] [--drain]} — the continuous scheduler daemon. */
    SERVE,
    /** {@code gnomish board [--dir] [--json] [--limit]} — read-only tracker board. */
    BOARD,
    /** {@code gnomish dashboard [--dir] [--out] [--watch]} — renders the HTML dashboard page. */
    DASHBOARD;

    private static final String RUN_TOKEN = "run";
    private static final String STATUS_TOKEN = "status";
    private static final String USAGE_TOKEN = "usage";
    private static final String TAKE_TOKEN = "take";
    private static final String SERVE_TOKEN = "serve";
    private static final String BOARD_TOKEN = "board";
    private static final String DASHBOARD_TOKEN = "dashboard";

    /**
     * @param args the raw application arguments, as Spring Boot parsed them
     * @return the requested subcommand, or {@link #RUN} when no positional token is present
     * @throws UsageException if the first positional token is present but names none of {@code
     *     run}, {@code status}, {@code usage}, {@code take}, {@code serve}, {@code board}
     */
    static Subcommand parse(ApplicationArguments args) {
        String token = firstPositionalToken(args);
        if (token == null) {
            return RUN;
        }
        return switch (token) {
            case RUN_TOKEN -> RUN;
            case STATUS_TOKEN -> STATUS;
            case USAGE_TOKEN -> USAGE;
            case TAKE_TOKEN -> TAKE;
            case SERVE_TOKEN -> SERVE;
            case BOARD_TOKEN -> BOARD;
            case DASHBOARD_TOKEN -> DASHBOARD;
            default ->
                throw new UsageException("'" + token + "' is not a gnomish subcommand: accepted forms are"
                        + " 'gnomish run', 'gnomish status', 'gnomish usage', 'gnomish take', 'gnomish serve',"
                        + " 'gnomish board', or 'gnomish dashboard'");
        };
    }

    private static @Nullable String firstPositionalToken(ApplicationArguments args) {
        for (String raw : args.getSourceArgs()) {
            if (!raw.startsWith("--")) {
                return raw;
            }
        }
        return null;
    }
}
