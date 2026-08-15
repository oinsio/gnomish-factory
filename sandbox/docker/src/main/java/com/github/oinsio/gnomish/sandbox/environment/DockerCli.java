package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
 * <p>Implements FR3, FR4, NFR-R1 of add-sandbox-core.
 */
class DockerCli {

    private static final String DAEMON_UNREACHABLE = "cannot connect to the docker daemon";

    private final String dockerBinary;

    DockerCli() {
        this("docker");
    }

    DockerCli(String dockerBinary) {
        this.dockerBinary = dockerBinary;
    }

    /**
     * Runs {@code docker <args...>}, capturing stdout/stderr as separate UTF-8
     * strings and the exit code. A non-zero exit is returned, not thrown —
     * callers decide what it means — <em>unless</em> the daemon reported itself
     * unreachable, which is thrown as {@link DockerUnavailableException}.
     *
     * @param args the docker subcommand and its arguments (no leading {@code docker})
     * @return the captured exit code and separate stdout/stderr
     * @throws DockerUnavailableException if the binary cannot launch or the daemon is unreachable
     */
    DockerResult run(List<String> args) {
        Process process = launch(args);
        String stdout = readFully(process.getInputStream());
        String stderr = readFully(process.getErrorStream());
        int exitCode = waitFor(process);
        if (exitCode != 0 && stderr.toLowerCase(Locale.ROOT).contains(DAEMON_UNREACHABLE)) {
            throw new DockerUnavailableException("docker daemon is unreachable: " + stderr.strip(), null);
        }
        return new DockerResult(exitCode, stdout, stderr);
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

    private Process launch(List<String> args) {
        ProcessBuilder builder = builder(args);
        try {
            return builder.start();
        } catch (IOException e) {
            throw new DockerUnavailableException("could not launch docker binary '" + dockerBinary + "'", e);
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

    private static String readFully(InputStream in) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            in.transferTo(buffer);
        } catch (IOException e) {
            // Stream read failure mid-command: keep whatever was captured so far (as GitProcessRunner).
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /**
     * PIT M4 documented exception: {@code @DoNotMutate} — the {@code
     * InterruptedException} catch is a genuine timing race (a thread interrupt
     * landing in the brief window while a docker management command runs), not
     * reliably reproducible in a unit test; the happy path (exit code returned)
     * is covered by every {@code DockerCli} run spec. Same rationale and
     * granularity as {@code GitProcessRunner#waitFor}.
     */
    @DoNotMutate
    private static int waitFor(Process process) {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
