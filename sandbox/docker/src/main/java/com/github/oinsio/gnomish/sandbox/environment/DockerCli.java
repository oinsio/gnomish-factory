package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.logtext.ShutdownPhase;
import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.subprocess.CaptureRunner;
import com.github.oinsio.gnomish.subprocess.Captured;
import com.github.oinsio.gnomish.subprocess.Termination;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * bound-subprocess-commands.
 */
class DockerCli {

    private static final Logger log = LoggerFactory.getLogger(DockerCli.class);

    private static final String DAEMON_UNREACHABLE = "cannot connect to the docker daemon";

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
    private final CaptureRunner captureRunner = new CaptureRunner();

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

    /**
     * @param dockerBinary the docker executable to invoke
     * @param commandTimeout the hard bound on a management command; the composition root passes
     *     the installation's {@code factory.docker-command-timeout}, and specs inject a
     *     sub-second one
     */
    DockerCli(String dockerBinary, Duration commandTimeout) {
        this.dockerBinary = dockerBinary;
        this.commandTimeout = commandTimeout;
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
     */
    DockerResult run(List<String> args) {
        Captured captured = capture(args);
        // Only a command that ran to completion can testify about the daemon: a killed one's exit
        // code is the signal's and its stderr is a partial capture, and neither may turn a timeout
        // or a shutdown into an outage report (FR6 of bound-subprocess-commands).
        if (captured.termination() == Termination.EXITED
                && captured.exitCode() != 0
                && captured.stderr().toLowerCase(Locale.ROOT).contains(DAEMON_UNREACHABLE)) {
            throw new DockerUnavailableException(
                    "docker daemon is unreachable: " + captured.stderr().strip(), null);
        }
        return new DockerResult(captured.exitCode(), captured.stdout(), captured.stderr(), captured.termination());
    }

    /**
     * Starts {@code docker <args...>} and returns the live process for streaming
     * — the {@code docker exec} seam behind {@link
     * TaskExecutionEnvironment#exec}. Merges the exec'd process's stderr into its
     * stdout when {@code mergeStderr} is set, matching the host adapter.
     *
     * @param args        the docker subcommand and its arguments (no leading {@code docker})
     * @param mergeStderr whether to fold stderr into the one output stream
     * @return the started process; never null
     * @throws ProcessStartException if the docker binary could not be launched
     */
    Process start(List<String> args, boolean mergeStderr) {
        ProcessBuilder builder = builder(args);
        builder.redirectErrorStream(mergeStderr);
        try {
            return builder.start();
        } catch (IOException e) {
            throw new ProcessStartException("could not start docker exec", e);
        }
    }

    /**
     * Runs the management command under the shared supervisor and reports a bound
     * that fired. A binary that cannot be launched at all is the daemon-outage
     * classification this class owns, not a result: there is no docker to ask.
     */
    private Captured capture(List<String> args) {
        long startedAt = System.nanoTime();
        try {
            Captured captured = captureRunner.run(builder(args), commandTimeout);
            report(captured.termination(), args, Duration.ofNanos(System.nanoTime() - startedAt));
            return captured;
        } catch (IOException e) {
            throw new DockerUnavailableException("could not launch docker binary '" + dockerBinary + "'", e);
        }
    }

    /**
     * Logs the one WARN a bound that fired owes an operator (NFR-O1, NFR-O2):
     * which command ended early, how long it ran, and — for a timeout — the
     * deadline they would raise to give it more. Only the subcommand is named,
     * never the full argv, which carries container keys and mount paths.
     */
    private void report(Termination termination, List<String> args, Duration elapsed) {
        if (termination == Termination.EXITED) {
            return;
        }
        String subcommand = args.isEmpty() ? "docker" : args.getFirst();
        if (termination == Termination.TIMED_OUT) {
            log.warn(
                    "docker command timed out and its process tree was killed: subcommand={},"
                            + " elapsed={}, deadline={}",
                    subcommand,
                    elapsed,
                    commandTimeout);
            return;
        }
        // FR9 of harden-logging-observability: same classification the git runner makes — an
        // interrupt during the shutdown phase is the stop, not an unexplained abort.
        log.warn(
                "docker command {} and its process tree was killed: subcommand={}, elapsed={}",
                ShutdownPhase.inProgress() ? "interrupted by the daemon shutdown" : "interrupted",
                subcommand,
                elapsed);
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
