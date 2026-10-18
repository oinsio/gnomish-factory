package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import com.github.oinsio.gnomish.sandbox.StderrPolicy;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.subprocess.CaptureRunner;
import com.github.oinsio.gnomish.subprocess.Captured;
import com.github.oinsio.gnomish.subprocess.CutCaptureException;
import com.github.oinsio.gnomish.subprocess.ProcessSupervisor;
import com.github.oinsio.gnomish.subprocess.StdinSource;
import com.github.oinsio.gnomish.subprocess.Termination;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Shells out to the real {@code docker} binary via {@link ProcessBuilder} — no
 * docker-java, no socket library (design D2), exactly as {@code GitProcessRunner}
 * shells out to {@code git}. Each command is a direct argv (never a shell
 * string), so keys and other values that flow into docker args carry no
 * injection risk. Two seams: {@link #run} for management commands
 * (create/inspect/remove/list) that capture output and finish, and {@link
 * #start} for {@code docker exec}, which returns a live {@link Process} the
 * caller streams.
 *
 * <p>Runtime outages are classified here, once (NFR-R1): a {@code docker} binary
 * that cannot be launched at all, or a daemon that answers "Cannot connect to
 * the Docker daemon", becomes a {@link DockerUnavailableException} — an
 * infrastructure failure — never a {@link DockerResult} a caller might mistake
 * for a command that merely exited non-zero.
 *
 * <p>Every management command is bounded (FR10, design D11): it runs under the
 * shared subprocess supervisor, which drains both streams concurrently with the
 * process and, on expiry or interruption, kills the whole process tree and reaps
 * it before returning a named {@link Termination}. The bound matters because a
 * management command is not always local — {@code docker run} on an image the
 * host does not have reaches a registry, and a registry that accepts the
 * connection and then never answers used to hold the whole take. {@link #start}
 * stays unbounded: its process is a live streaming exec whose caller
 * ({@code HostExecHandle}, {@link ContainerFileChannel}) owns the wait.
 *
 * <p>Implements FR3, FR4, NFR-R1 of add-sandbox-core; FR6, FR10, NFR-O1 of
 * bound-subprocess-commands; NFR-O3 of fix-terminal-receipt-convergence.
 */
class DockerCli {

    /**
     * The documented default for a management command (FR5): generous enough
     * that a real {@code docker run} pulling a real image finishes under it,
     * short enough that a wedged daemon or registry does not hold a take for the
     * rest of the night. An installation raises it through {@code
     * factory.docker-command-timeout}.
     */
    static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofMinutes(5);

    private final String dockerBinary;
    private final Duration commandTimeout;
    private final CaptureRunner captureRunner;
    private final DockerCommandReport report;

    DockerCli() {
        this("docker");
    }

    DockerCli(String dockerBinary) {
        this(dockerBinary, DEFAULT_COMMAND_TIMEOUT);
    }

    /**
     * The real {@code docker} binary under an explicit deadline — what this package's public
     * entry points hand the installation's {@code factory.docker-command-timeout} through (FR5,
     * design D8 of bound-subprocess-commands).
     *
     * @param commandTimeout the hard bound on a management command
     */
    DockerCli(Duration commandTimeout) {
        this("docker", commandTimeout);
    }

    DockerCli(String dockerBinary, Duration commandTimeout) {
        this(dockerBinary, commandTimeout, new CaptureRunner());
    }

    /**
     * @param dockerBinary the docker executable to invoke
     * @param commandTimeout the hard bound on a management command (the installation's {@code
     *     factory.docker-command-timeout}; specs inject a sub-second one)
     * @param captureRunner the launch, the wait and the post-exit bound every management command
     *     runs under, and the launch owner {@link #start} starts every exec client through — one
     *     owner for both, so a management command and a box command cannot be launched by two
     *     different forms; a spec hands in one over a fake launch (design D16 of
     *     fix-terminal-receipt-convergence)
     */
    DockerCli(String dockerBinary, Duration commandTimeout, CaptureRunner captureRunner) {
        this.dockerBinary = dockerBinary;
        this.commandTimeout = commandTimeout;
        this.captureRunner = captureRunner;
        this.report = new DockerCommandReport(commandTimeout);
    }

    /**
     * Runs {@code docker <args...>} under the command deadline, capturing
     * stdout/stderr as separate UTF-8 strings, the exit code, and how the
     * invocation ended. A non-zero exit is returned, not thrown — callers decide
     * what it means, after reading {@link DockerResult#termination()} first —
     * <em>unless</em> the daemon reported itself unreachable, which is thrown as
     * {@link DockerUnavailableException}.
     *
     * @param args the docker subcommand and its arguments (no leading {@code docker})
     * @return the named termination, the captured exit code and separate stdout/stderr
     * @throws DockerUnavailableException if the binary cannot launch or the daemon is unreachable
     * @throws CutCaptureException if docker exited with code zero while a descendant still held its
     *     output at the post-exit bound, so the capture may be a prefix
     */
    DockerResult run(List<String> args) {
        Captured captured = capture(args);
        // Only a command that ran to completion can testify about the daemon: a killed one's exit
        // code is the signal's and its stderr is a partial capture, and neither may turn a timeout
        // or a shutdown into an outage report (FR6 of bound-subprocess-commands).
        if (captured.termination() == Termination.EXITED
                && captured.exitCode() != 0
                && DockerUnavailableException.reportsDaemonUnreachable(captured.stderr())) {
            // The mint for what the daemon said about itself: `Captured` is still a plain String
            // at this point by design D3, so the carrier is made here, at the first factory
            // reader — the typed parameter is what keeps it out of the message unrendered.
            throw new DockerUnavailableException(
                    "docker daemon is unreachable", UntrustedText.subprocess(captured.stderr()));
        }
        return DockerResult.of(captured.exitCode(), captured.stdout(), captured.stderr(), captured.termination());
    }

    /**
     * Starts {@code docker <args...>} and returns the live process for streaming
     * — the {@code docker exec} seam behind {@link
     * TaskExecutionEnvironment#exec}. Applies {@code stderr} to the client on the host
     * side, matching the host adapter (design D18 of fix-terminal-receipt-convergence):
     * merged into its stdout, a pipe of its own, or a host file — the {@code docker exec}
     * client's stderr carries the exec'd process's, so the file holds the box's. The client
     * is started by the launch owner the capture runner holds, which sets its
     * standard input from {@code stdin} (design D20 of fix-terminal-receipt-convergence):
     * the file the {@code docker exec -i} client forwards into the box, or nothing.
     *
     * @param args        the docker subcommand and its arguments (no leading {@code docker})
     * @param stderr      where the client's stderr goes
     * @param stdin       what the client reads: {@link StdinSource#NONE}, or the whole input
     * @return the started process; never null
     * @throws ProcessStartException if the docker binary could not be launched
     */
    Process start(List<String> args, StderrPolicy stderr, StdinSource stdin) {
        ProcessBuilder builder = builder(args);
        stderr.applyTo(builder);
        try {
            return captureRunner.launch().start(builder, stdin);
        } catch (IOException e) {
            throw new ProcessStartException("could not start docker exec", e);
        }
    }

    /**
     * The supervisor a caller of {@link #start} waits under, and whose post-exit bound
     * it joins that process's output with (design D17 of fix-terminal-receipt-convergence)
     * — the one the management commands run under, never a bound of the caller's own.
     *
     * @return the capture runner's supervisor
     */
    ProcessSupervisor supervisor() {
        return captureRunner.supervisor();
    }

    /**
     * Runs the management command under the shared supervisor and reports what its
     * outcome owes the operator ({@link DockerCommandReport}). A binary that cannot be launched at all is the daemon-outage
     * classification this class owns, not a result: there is no docker to ask.
     *
     * <p>A clean exit whose drain the post-exit bound cut is refused by the capture runner; the
     * holder is logged once here and the refusal rethrown (NFR-O3, design D15 as amended
     * 2026-10-17), so no parser of the result takes the prefix for the whole output.
     */
    private Captured capture(List<String> args) {
        long startedAt = System.nanoTime();
        try {
            Captured captured = captureRunner.run(builder(args), commandTimeout);
            report.report(captured, args, Duration.ofNanos(System.nanoTime() - startedAt));
            return captured;
        } catch (IOException e) {
            throw new DockerUnavailableException("could not launch docker binary '" + dockerBinary + "'", e);
        } catch (CutCaptureException cut) {
            report.cut(cut, args);
            throw cut;
        }
    }

    private ProcessBuilder builder(List<String> args) {
        // No initial-capacity hint: it would be a non-behavioural arithmetic mutant (an ArrayList
        // grows regardless), so the list is built without one to keep the mutation gate meaningful.
        List<String> commandLine = new ArrayList<>();
        commandLine.add(dockerBinary);
        commandLine.addAll(args);
        return new ProcessBuilder(commandLine);
    }
}
