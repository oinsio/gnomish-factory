package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.logtext.ShutdownPhase;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.subprocess.Captured;
import com.github.oinsio.gnomish.subprocess.CutCaptureException;
import com.github.oinsio.gnomish.subprocess.Termination;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What one {@link DockerCli} management command owes the operator once it is over: the one WARN a
 * bound that fired owes (NFR-O1, NFR-O2) — which command ended early, how long it ran, and, for a
 * timeout, the deadline to raise — or, for a clean exit the capture runner refused because the
 * post-exit bound cut its drain, the one WARN attributing the holder before the refusal is rethrown
 * (NFR-O3, design D15 of fix-terminal-receipt-convergence as amended 2026-10-17). A cut on a failed
 * or killed command is that failure's diagnostics, named in its stderr, so an invocation gets one
 * line at most. A healthy command gets none.
 *
 * <p>Only the subcommand is named, never the full argv, which carries container keys and mount
 * paths.
 *
 * <p>A sweep of the command's process group that found a survivor gets its DEBUG line here too
 * ({@link HostSweep}, design D19).
 *
 * <p>Implements FR6, FR10, NFR-O1 of bound-subprocess-commands; FR21, NFR-O3 of
 * fix-terminal-receipt-convergence.
 *
 * @param commandTimeout the deadline the command ran under, named by a timeout's line
 */
record DockerCommandReport(Duration commandTimeout) {

    private static final Logger log = LoggerFactory.getLogger(DockerCommandReport.class);

    /**
     * Logs the holder of a refused cut capture: which streams it kept open, for which subcommand,
     * past which bound. The caller rethrows the refusal; this line attributes the holder only.
     *
     * @param cut the capture runner's refusal
     * @param args the docker subcommand and its arguments (no leading {@code docker})
     */
    void cut(CutCaptureException cut, List<String> args) {
        HostSweep.report(cut.captured().sweep(), "docker " + subcommand(args));
        // throwable-not-subject: the refusal is rethrown by the caller, which decides on it.
        log.warn(
                OperatorEvent.DOCKER_CAPTURE_TRUNCATED.head()
                        + "docker command exited with code 0 but a descendant still held its {} when the post-exit"
                        + " bound expired; the capture is refused as a possible prefix: subcommand={}, bound={}",
                cut.streams().phrase(),
                subcommand(args),
                cut.bound());
    }

    /**
     * Logs what {@code captured} owes, if anything: the sweep's DEBUG line when it found a survivor
     * (NFR-O3, design D19), and the WARN of a bound that fired.
     *
     * @param captured how the command ended, and what the group sweep found after it
     * @param args the docker subcommand and its arguments (no leading {@code docker})
     * @param elapsed how long the command ran
     */
    void report(Captured captured, List<String> args, Duration elapsed) {
        String subcommand = subcommand(args);
        HostSweep.report(captured.sweep(), "docker " + subcommand);
        Termination termination = captured.termination();
        if (termination == Termination.EXITED) {
            return;
        }
        if (termination == Termination.TIMED_OUT) {
            log.warn(
                    OperatorEvent.DOCKER_COMMAND_TIMED_OUT.head()
                            + "docker command timed out and its process tree was killed: subcommand={},"
                            + " elapsed={}, deadline={}",
                    subcommand,
                    elapsed,
                    commandTimeout);
            return;
        }
        // FR9 of harden-logging-observability: same classification the git runner makes — an
        // interrupt during the shutdown phase is the stop, not an unexplained abort.
        log.warn(
                OperatorEvent.DOCKER_COMMAND_KILLED.head()
                        + "docker command {} and its process tree was killed: subcommand={}, elapsed={}",
                ShutdownPhase.inProgress() ? "interrupted by the daemon shutdown" : "interrupted",
                subcommand,
                elapsed);
    }

    private static String subcommand(List<String> args) {
        return args.isEmpty() ? "docker" : args.getFirst();
    }
}
