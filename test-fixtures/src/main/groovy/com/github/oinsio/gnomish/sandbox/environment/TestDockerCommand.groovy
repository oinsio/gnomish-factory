package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * One {@code docker} command a container fixture or spec runs on the test machine's daemon, with
 * its exit code and its merged output.
 *
 * <p>The child gets the test environment, never the operator's ({@code testing.md}, "Test processes
 * never inherit the operator's environment"): its environment is {@link TestChildEnvironment#cleared},
 * which keeps the Docker client variables the build composed. It reads no pipe: standard input is
 * {@code /dev/null} and the merged output goes to a file read after the exit, so no thread parks in
 * a read of a child's pipe.
 *
 * <p>It is the one place in the container fixtures that captures a child's output
 * ({@code RawCaptureOwners} in {@code :bootstrap}): {@link #command} runs any argv this way, and the
 * docker methods and {@link SandboxBaseImage}'s run of {@code scripts/image-contract-build-args.sh}
 * (the argument list of the base's {@code docker build}) both go through it, so the launch shape
 * and the read live once.
 *
 * <p>Implements FR10 of add-sandbox-base-image (test infrastructure of the base image's specs).
 */
final class TestDockerCommand {

    /** The bound on one command; an image build from source is the longest a fixture runs. */
    static final long TIMEOUT_MINUTES = 30

    /** What a finished command left: its exit code and its merged standard output and error. */
    static final class Result {

        final int exitCode
        final String output

        Result(int exitCode, String output) {
            this.exitCode = exitCode
            this.output = output
        }

        /** The output, asserting the command succeeded; the failure names the command's output. */
        String success() {
            assert exitCode == 0: "docker exited ${exitCode}:\n${output}"
            output
        }
    }

    private TestDockerCommand() {}

    /** Runs {@code docker <args>} to its exit, within {@link #TIMEOUT_MINUTES}. */
    static Result run(List<String> args) {
        command(['docker'] + args, Duration.ofMinutes(TIMEOUT_MINUTES))
    }

    /**
     * Runs {@code argv} to its exit, within {@code timeout}: the environment through
     * {@link TestChildEnvironment#cleared}, standard input from {@code /dev/null}, standard output
     * and error merged into a file read after the exit. A child still running at the bound is
     * killed and the run fails with what it printed.
     */
    static Result command(List<String> argv, Duration timeout) {
        Path log = Files.createTempFile('gnomish-child-', '.log')
        try {
            def builder = new ProcessBuilder(argv)
                    .redirectErrorStream(true)
                    .redirectInput(ProcessBuilder.Redirect.from(new File('/dev/null')))
                    .redirectOutput(log.toFile())
            TestChildEnvironment.cleared(builder)
            def process = builder.start()
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                assert false: "${argv} did not finish within ${timeout}:\n${Files.readString(log)}"
            }
            return new Result(process.exitValue(), Files.readString(log))
        } finally {
            Files.deleteIfExists(log)
        }
    }

    /** Runs {@code docker <args>}, asserting success; returns the output. */
    static String succeed(List<String> args) {
        run(args).success()
    }

    /**
     * Runs {@code script} with {@code sh -c} in a throwaway container of {@code image}, as
     * {@code user}, with no network.
     */
    static Result shell(String image, String user, String script) {
        run([
            'run',
            '--rm',
            '--network',
            'none',
            '--user',
            user,
            image,
            'sh',
            '-c',
            script
        ])
    }
}
