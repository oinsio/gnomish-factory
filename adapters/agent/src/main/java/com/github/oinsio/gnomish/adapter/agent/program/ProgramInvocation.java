package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.app.describe.ProgramBounds;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import com.github.oinsio.gnomish.sandbox.BoundedCapture;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One {@code program} process, run to its end under the factory's bounds (FR5, NFR-R2 of
 * define-executor-contract; design D3): spawned only through the environment's one process seam,
 * its request on standard input as the command's stdin — which the launch owner hands the child as
 * a file, never a pipe a factory thread writes into — its stderr to the file the caller's policy
 * names, its stdout a capped log. Without {@code streams_progress} stdout is read by the one bounded
 * capture ({@link BoundedCapture}) and the wait is bounded by the overall bound alone; with it, stdout is
 * read live by {@link ProgressLines} and {@link ProgressWait} applies the silence bound too. The
 * handle kills the process tree when a bound expires; this class owns no reader, pump or close of a
 * child's pipe, and joins each drain with the handle's post-exit bound only.
 *
 * <p>Stdout is never parsed: what a run printed is captured as a carrier, at most {@link
 * #STDOUT_CAP_BYTES} bytes of it, and leaves it only through the log exit — one DEBUG line, flattened
 * and bounded to the tail by {@link UntrustedText#forLog()}, so the log record is one line however
 * many lines the run printed.
 *
 * <p>Implements FR5, NFR-O2, NFR-R2 of define-executor-contract.
 */
final class ProgramInvocation {

    /** The most bytes of a non-streaming run's stdout kept for its log line. */
    static final int STDOUT_CAP_BYTES = 64 * 1024;

    private static final Logger log = LoggerFactory.getLogger(ProgramInvocation.class);

    private final TaskExecutionEnvironment environment;
    private final ProgramBounds bounds;
    private final TimeEquipment time;

    /**
     * @param environment where the process runs; materialized; never null
     * @param bounds the factory's bounds on the run; never null
     * @param time the run's time equipment; never null
     */
    ProgramInvocation(TaskExecutionEnvironment environment, ProgramBounds bounds, TimeEquipment time) {
        this.environment = environment;
        this.bounds = bounds;
        this.time = time;
    }

    /**
     * Runs the process to its end.
     *
     * @param executor the executor's name, for the log lines; never blank
     * @param command the argv, the factory-set variables, the request on stdin and the stderr policy;
     *     never null
     * @param streamsProgress whether the executor's describe declared {@code streams_progress}
     * @return how the process ended; never null
     * @throws ProcessStartException when the environment refuses to start the process
     */
    ProgramEnd run(String executor, ExecCommand command, boolean streamsProgress) {
        ExecHandle handle = environment.exec(command);
        if (streamsProgress) {
            try (ProgressLines lines = ProgressLines.start(handle.output(), time.clock(), executor)) {
                ProgramEnd end = new ProgressWait(bounds, time).await(handle, lines);
                lines.await(handle.postExitBound());
                return end;
            }
        }
        BoundedCapture stdout =
                BoundedCapture.of(handle, "program-stdout", STDOUT_CAP_BYTES, bounds.overall(), time.clock());
        logStdout(executor, stdout.output());
        return switch (stdout.ended()) {
            case ExecHandle.Wait.Exited exited -> new ProgramEnd.Exited(handle.waitForExit(), exited.wallTime());
            case ExecHandle.Wait.TimedOut ignored -> new ProgramEnd.BoundExpired("overall", bounds.overall());
            case ExecHandle.Wait.Interrupted ignored -> new ProgramEnd.Interrupted();
        };
    }

    private static void logStdout(String executor, UntrustedText captured) {
        if (captured.isBlank()) {
            return;
        }
        log.debug(
                "program executor '{}' wrote on stdout (a log, never its result), its tail: {}",
                executor,
                captured.forLog());
    }
}
