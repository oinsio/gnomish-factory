package com.github.oinsio.gnomish.subprocess;

import com.github.oinsio.buildchecks.atomicwrite.NonAtomicWrite;
import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * The launch owner (design D16, D19, D20 of fix-terminal-receipt-convergence): the one production
 * file that starts a {@link ProcessBuilder} and the one that sets a child's standard input, reached
 * only through {@link ProcessLaunch#grouped(Path)}.
 *
 * <p>The child's stdin is never a pipe. {@link StdinSource#NONE} is {@code /dev/null}. Content is a
 * file created by {@link Files#createTempFile} in the owner's input directory — atomically, owner-only
 * {@code 0600} on POSIX — written in full, redirected into the child, and deleted once {@code start()}
 * returns, on the failure path too: the child and its descendants read it to the end through the
 * descriptor they inherited, whatever the parent does next, and no factory thread writes into a pipe
 * a descendant may hold. A file that cannot be created or written fails the launch with that
 * {@link IOException} before any process exists.
 *
 * <p>The argv is prefixed with the platform's launcher ({@link HostLauncher}, design D19), so the
 * child leads a process group of its own whose id is its pid; a missing helper fails the invocation
 * naming it before any process exists — never an ungrouped run. Before the prefix, the command's
 * executable and its {@code #!} interpreter chain are resolved ({@link ExecutableLookup}, design D19
 * as amended 2026-10-17, twice), against the child's {@code PATH} — the builder's environment, else
 * the JVM's — so an executable that cannot run throws here, as {@code start()} threw before a
 * launcher existed. The residue the lookup cannot see (a {@code noexec} mount, {@code ETXTBSY}, a
 * race with the exec) reaches the waiter as the launcher's exit, which {@link
 * HostLauncher#couldNotExec} names a launch failure only by the code together with the launcher's
 * own line. The caller's builder is handed back with the command it was given.
 *
 * <p>The group sweep's {@code kill} ({@link ProcessGroupSweep}) is started here too, and ungrouped:
 * a grouped {@code kill} would be a group to sweep in turn. It is the second and last start in this
 * file, and only its exit code is read (task 8.20).
 *
 * <p>Every child this owner started is recorded in the live group registry ({@link LiveGroups},
 * design D19, fourth amendment) once {@code start()} has returned, so the shutdown step can sweep a
 * group no waiter has swept yet; this file and the sweep are the registry's only writers.
 *
 * <p>Implements FR18, FR21, FR22, FR23 of fix-terminal-receipt-convergence (D19 as amended
 * 2026-10-17, second and fourth).
 */
@NonAtomicWrite("scratch handed to a subprocess: the stdin file is read only by the child it is"
        + " redirected into, through the descriptor it inherited, and deleted once start() returns, so"
        + " no factory instance ever reads it back")
final class GroupedLaunch implements ProcessLaunch {

    /** The child's stdin when it is given nothing: end-of-file at once, no pipe to inherit. */
    private static final File NO_INPUT = new File("/dev/null");

    /** How long one {@code kill} of the sweep is waited for; it exits at once (design D19). */
    private static final long KILL_BOUND_MILLIS = 2_000;

    private final Path inputDirectory;
    private final @Nullable String jvmPath;
    private final HostLauncher launcher;
    private final LiveGroups groups;

    /**
     * @param inputDirectory where a child's input file lives for the length of its launch
     */
    GroupedLaunch(Path inputDirectory) {
        this(inputDirectory, System.getProperty("os.name", ""), System.getenv("PATH"));
    }

    /**
     * @param inputDirectory where a child's input file lives for the length of its launch
     * @param osName the platform, as {@code os.name} names it, which picks the launcher
     * @param jvmPath the factory's own {@code PATH}: where {@code setsid} is looked up, and the
     *     child's when its environment names none
     */
    GroupedLaunch(Path inputDirectory, String osName, @Nullable String jvmPath) {
        this(inputDirectory, jvmPath, new HostLauncher(osName, jvmPath, HostLauncher.KILL));
    }

    /**
     * @param inputDirectory where a child's input file lives for the length of its launch
     * @param jvmPath the child's {@code PATH} when its environment names none
     * @param launcher the host's process-group helpers
     */
    GroupedLaunch(Path inputDirectory, @Nullable String jvmPath, HostLauncher launcher) {
        this(inputDirectory, jvmPath, launcher, LiveGroups.PROCESS);
    }

    /**
     * @param inputDirectory where a child's input file lives for the length of its launch
     * @param jvmPath the child's {@code PATH} when its environment names none
     * @param launcher the host's process-group helpers
     * @param groups the live group registry every started child's group is recorded in: {@link
     *     LiveGroups#PROCESS} in production, a spec's own otherwise
     */
    GroupedLaunch(Path inputDirectory, @Nullable String jvmPath, HostLauncher launcher, LiveGroups groups) {
        this.inputDirectory = inputDirectory;
        this.jvmPath = jvmPath;
        this.launcher = launcher;
        this.groups = groups;
    }

    @Override
    public Process start(ProcessBuilder builder, StdinSource stdin) throws IOException {
        List<String> command = builder.command();
        String childPath = builder.environment().get("PATH");
        Path executable = ExecutableLookup.resolve(
                command.getFirst(), builder.directory(), childPath == null ? jvmPath : childPath);
        List<String> launched = new ArrayList<>(launcher.prefix());
        launched.add(executable.toString());
        launched.addAll(command.subList(1, command.size()));
        builder.command(launched);
        Process started;
        try {
            started = switch (stdin) {
                case StdinSource.Nothing nothing -> startOn(builder, NO_INPUT);
                case StdinSource.Content content -> startFed(builder, content.bytes());
            };
        } finally {
            builder.command(command);
        }
        // Only once start() returned: a launch that threw started no group, and registers none.
        groups.register(started.pid());
        return started;
    }

    /** The group sweep's signal over this host's {@code kill} (design D19). */
    static ProcessGroupSweep.GroupSignal groupSignal() {
        return new GroupedLaunch(Path.of(System.getProperty("java.io.tmpdir")))::signalGroup;
    }

    /**
     * {@code kill -<signal> -- -<group>}, started ungrouped, its input {@code /dev/null} and its
     * output discarded: the answer is the exit code alone, never the text of {@code kill}'s error.
     * The wait consumes no interrupt — the sweep runs on the interrupted path too — and is bounded
     * by {@link #KILL_BOUND_MILLIS}; a {@code kill} that could not start or did not end has no
     * answer.
     */
    ProcessGroupSweep.Answer signalGroup(String signal, long group) {
        if (group <= 1) {
            throw new IllegalArgumentException("no group is signalled by a pid of one or less: " + group);
        }
        Process kill;
        try {
            kill = new ProcessBuilder(launcher.kill(), "-" + signal, "--", "-" + group)
                    .redirectOutput(Redirect.DISCARD)
                    .redirectError(Redirect.DISCARD)
                    .redirectInput(NO_INPUT)
                    .start();
        } catch (IOException e) {
            return ProcessGroupSweep.Answer.UNSENT;
        }
        kill.onExit()
                .completeOnTimeout(kill, KILL_BOUND_MILLIS, TimeUnit.MILLISECONDS)
                .join();
        if (kill.isAlive()) {
            kill.destroyForcibly();
            return ProcessGroupSweep.Answer.UNSENT;
        }
        return kill.exitValue() == 0 ? ProcessGroupSweep.Answer.ANSWERED : ProcessGroupSweep.Answer.REFUSED;
    }

    private Process startFed(ProcessBuilder builder, byte[] input) throws IOException {
        Path file = Files.createTempFile(inputDirectory, "gnomish-stdin-", ".input");
        try {
            Files.write(file, input);
            return startOn(builder, file.toFile());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static Process startOn(ProcessBuilder builder, File input) throws IOException {
        return builder.redirectInput(input).start();
    }
}
