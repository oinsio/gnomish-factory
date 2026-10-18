package com.github.oinsio.gnomish.subprocess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The host's process-group helpers (design D19 of fix-terminal-receipt-convergence): which binary
 * puts a child in a group of its own, the words it uses when its exec fails, and which {@code kill}
 * the group sweep signals with. The launch owner, {@link GroupedLaunch}, is its one user; the two
 * starts — the child's and the sweep's {@code kill} — stay in the owner's file.
 *
 * <p>The child leads a process group whose id is its pid: {@code setsid} on Linux (util-linux),
 * resolved on the JVM's {@code PATH} — the launcher is the factory's tool, not the child's, whose
 * composed environment may name no {@code PATH} at all; on macOS, which ships no {@code setsid(1)},
 * the OS's own {@code /usr/bin/perl} by its fixed path, so no other {@code perl} stands in for it.
 * The one-liner execs through the indirect-object form, {@code exec { $ARGV[0] } @ARGV}, because a
 * one-element {@code exec LIST} holding a shell metacharacter would go through {@code /bin/sh}. It
 * runs under {@code no warnings}, so perl's own words never reach the child's stderr, and when the
 * exec fails it writes the project's own refusal line, {@code gnomish-launch: cannot exec <path>:
 * <strerror>}, and exits as util-linux's {@code errexec} does: 127 for {@code ENOENT}, 126 for any
 * other errno (design D19 as amended 2026-10-17, second) — the only text it can write, and only on
 * a failed exec. The JVM's child is never a group leader, so either launcher execs in place:
 * neither {@code --fork} nor {@code --wait} is passed, and {@link Process#pid()} is the program's
 * and the group's.
 *
 * <p>The sweep's signaller is {@code /bin/kill} on both platforms, by its fixed path: macOS ships
 * it, and a Linux host has it from procps or util-linux. It is checked with the launcher, so a host
 * that could group a child but never sweep its group starts nothing (task 8.20). A missing helper,
 * or a platform with none, fails the invocation naming it before any process exists — never an
 * ungrouped run.
 *
 * <p>Implements FR21, FR22 of fix-terminal-receipt-convergence (D19 as amended 2026-10-17).
 */
final class HostLauncher {

    /** The group sweep's signaller, by its fixed path on both platforms. */
    static final Path KILL = Path.of("/bin/kill");

    /** macOS's launcher: the OS's own perl, by its fixed path. */
    private static final String PERL = "/usr/bin/perl";

    /**
     * The perl one-liner: a new session, then the program exec'd in place, its argv verbatim; on a
     * failed exec the refusal line and the errno's code, perl itself silent.
     */
    private static final String PERL_SETSID = "no warnings; use POSIX (); POSIX::setsid(); exec { $ARGV[0] } @ARGV;"
            + " my $e = $! + 0; print STDERR \"gnomish-launch: cannot exec $ARGV[0]: $!\\n\";"
            + " exit($e == POSIX::ENOENT() ? 127 : 126)";

    /** Linux's launcher, util-linux's {@code setsid}, looked up on the JVM's {@code PATH}. */
    private static final String SETSID = "setsid";

    /** util-linux {@code setsid}'s own words when its exec fails ({@code errexec}). */
    private static final String SETSID_REFUSAL = "setsid: failed to execute ";

    /** The perl launcher's own refusal line, the project's words ({@link #PERL_SETSID}). */
    private static final String SENTINEL = "gnomish-launch: cannot exec ";

    private final String osName;
    private final @Nullable String jvmPath;
    private final Path kill;

    /**
     * @param osName the platform, as {@code os.name} names it, which picks the launcher
     * @param jvmPath the factory's own {@code PATH}, where {@code setsid} is looked up
     * @param kill the sweep's signaller: {@link #KILL} in production
     */
    HostLauncher(String osName, @Nullable String jvmPath, Path kill) {
        this.osName = osName;
        this.jvmPath = jvmPath;
        this.kill = kill;
    }

    /** Returns the sweep's signaller, as checked by {@link #prefix()}. */
    String kill() {
        return kill.toString();
    }

    /**
     * Whether an exit is the launcher's refusal to exec the program rather than the program's own
     * result: the code launchers give it, 126 or 127, together with the launcher's own line on
     * stderr — the perl launcher's sentinel or util-linux's {@code failed to execute} — never the
     * code alone, which any program, and {@code docker run} for the contained command, may return.
     */
    static boolean couldNotExec(Termination termination, int exitCode, String stderr) {
        if (termination != Termination.EXITED || (exitCode != 126 && exitCode != 127)) {
            return false;
        }
        return stderr.lines().anyMatch(line -> line.startsWith(SENTINEL) || line.startsWith(SETSID_REFUSAL));
    }

    /** The platform's launcher prefix, it and the sweep's {@code kill} checked before anything starts. */
    List<String> prefix() throws IOException {
        if (!Files.isExecutable(kill)) {
            throw new IOException(
                    "the process group sweep's " + kill + " is missing, and no host process is started unswept");
        }
        if (osName.startsWith("Mac")) {
            if (!Files.isExecutable(Path.of(PERL))) {
                throw new IOException("process-group launcher " + PERL + " is missing: macOS ships it, and no host"
                        + " process is started ungrouped");
            }
            return List.of(PERL, "-e", PERL_SETSID);
        }
        if (osName.equals("Linux")) {
            try {
                return List.of(ExecutableLookup.resolve(SETSID, null, jvmPath).toString());
            } catch (IOException e) {
                throw new IOException(
                        "process-group launcher " + SETSID + " (util-linux) is missing from the"
                                + " factory's PATH, and no host process is started ungrouped",
                        e);
            }
        }
        throw new IOException(
                "no process-group launcher for os.name '" + osName + "': no host process is started" + " ungrouped");
    }
}
