package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.git.GitVersionCheck;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.status.MdcEventListener;
import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * The whole-CLI entrypoint (design D10): runs on the {@code ApplicationRunner} thread Spring Boot
 * calls after context refresh. {@link SubcommandDispatch} first tries {@code status}/{@code
 * usage}/{@code take} (FR13, FR14 of add-git-workflow; FR9 of add-tracker-port) — a bare {@code
 * gnomish take} is never confused with a bare {@code gnomish run} no-op, since the leading
 * positional token settles the subcommand before {@link #RUN_FLAGS} is ever consulted. For
 * {@code run} — explicit or implicit — with none of its flags present, this runner no-ops (FR12);
 * otherwise {@link ManualRunDrive} drives the full pipeline: parse → load {@code .gnomish/} →
 * dispatch by {@code --resume} presence, then by {@link RunArguments#mode()}.
 *
 * <p>The runner composes nothing (FR7, design D6 of collapse-composition-roots): the subcommand
 * dispatch and the manual-run drive arrive assembled from {@link ManualRunConfiguration} and
 * {@link TrackerCommandConfiguration}. Exception reporting (UX3) is delegated to {@link
 * RunExceptionReporting}; the {@code taskId} MDC key is cleared in {@code finally}.
 *
 * <p>Implements FR1, FR2, FR4, FR9, FR12, NFR-O1, UX3, D9, D10 of add-manual-run; FR5-FR8, FR13,
 * FR14, UX1-UX4, design D8, D9 of add-git-workflow; FR7 of collapse-composition-roots.
 */
// Null-marked explicitly (JSpecify): this module carries no package-info, and the application
// module's one does not reach this source root, so without the class-level marker the
// ApplicationRunner override here reads as unannotated against its null-marked supertype.
@NullMarked
@Component
public final class ManualRunRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ManualRunRunner.class);

    private static final List<String> RUN_FLAGS = List.of(
            "dir",
            "task",
            "task-file",
            "task-id",
            "from-stage",
            "interactive",
            "mode",
            "base",
            "resume",
            "discard-work");

    /**
     * The MDC key this runner sets once {@code taskId} is known (design D9, task 8.2).
     * Package-private: {@link ManualRunDrive} sets it once the ad-hoc task is synthesized, and the
     * composition root hands it to the resume runners and the slot wiring.
     * The spelling is the one {@code MdcAwareThread.TASK_ID_KEY} (module {@code :logtext}) owns
     * for the log pattern and for every daemon that scopes per-task work to it (FR8 of
     * harden-logging-observability). It is repeated rather than referenced because this module
     * reaches {@code :logtext} only through {@code :application}'s transitive edge, and the
     * composition root taking a production dependency on a leaf for one constant is the wrong
     * trade; {@code ManualRunRunnerMdcKeySpec} asserts the two stay equal.
     */
    static final String TASK_ID_KEY = "taskId";
    /**
     * Printed at the start of an in-place run, before the pipeline loads (FR7, UX4).
     * Package-private: printed from {@link ManualRunDrive}.
     */
    static final String IN_PLACE_REMINDER =
            "in-place mode: no git, no resume — the task's progress lives only in this process;"
                    + " killing it loses all work.";

    /** The git version floor every command passes through before dispatch (FR10 of own-git-transfer-argv). */
    private final GitVersionCheck gitVersionCheck;

    private final SubcommandDispatch subcommandDispatch;
    private final ManualRunDrive drive;
    /** The console owner bound to standard error; see {@link ManualRunConfiguration#errorConsoleIO}. */
    private final ConsoleIO errorConsole;

    ManualRunRunner(
            GitVersionCheck gitVersionCheck,
            SubcommandDispatch subcommandDispatch,
            ManualRunDrive drive,
            @Qualifier("errorConsoleIO") ConsoleIO errorConsoleIO) {
        this.gitVersionCheck = gitVersionCheck;
        this.subcommandDispatch = subcommandDispatch;
        this.drive = drive;
        this.errorConsole = errorConsoleIO;
    }

    /** No relevant flag present → no-op (FR12); otherwise drives the run (see class javadoc). */
    @Override
    public void run(ApplicationArguments args) throws IOException, InterruptedException {
        try {
            RunExceptionReporting.run(
                    () -> {
                        // FR10 of own-git-transfer-argv: the floor is checked before any
                        // subcommand dispatches, so run, take and serve all pass through it and
                        // no transfer, claim or tracker write precedes a refusal.
                        gitVersionCheck.verify();
                        if (subcommandDispatch.dispatchNonRun(args)
                                || RUN_FLAGS.stream().noneMatch(args::containsOption)) {
                            return;
                        }
                        drive.drive(args);
                    },
                    log,
                    errorConsole);
        } finally {
            MDC.remove(TASK_ID_KEY);
            // FR8: backstop for a run that ended without TaskFinished (an abort thrown out of
            // the engine), so nothing on this thread inherits a finished attempt's scope.
            MdcEventListener.clearAttemptScope();
        }
    }
}
