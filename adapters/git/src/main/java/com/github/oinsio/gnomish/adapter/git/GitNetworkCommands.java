package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.DoNotMutate;
import java.util.Map;

/**
 * Which git subcommands reach a remote, and what a network invocation carries so that git's own
 * no-progress detection — not the process deadline — is what governs a transfer that has stopped
 * moving (design D1, D5 of bound-subprocess-commands).
 *
 * <p>The deadline {@link GitProcessRunner} applies on top of these settings is the backstop for a
 * wedged process, not the primary mechanism: a transfer that keeps making progress must never be
 * killed for taking long (G3), and a dead connection must not sit for the whole deadline when git
 * can notice it in a minute. The two transports the factory uses need different settings — HTTP
 * aborts below a throughput floor sustained over a window, SSH needs connect and keepalive limits
 * plus {@code BatchMode}, which closes ssh's own prompt paths that emptying the askpass hooks
 * cannot reach. {@code git://} has neither, and no factory path configures such an origin (Q6).
 *
 * <p>Everything here is per invocation: the HTTP settings ride in as {@code -c} options and the
 * SSH one as a child-process environment variable, so no file of the operator's git configuration
 * is written (NFR-S1), and an operator who already set {@code GIT_SSH_COMMAND} — a wrapper, a jump
 * host — keeps theirs.
 *
 * <p>Implements FR1, FR4, NFR-S1 of bound-subprocess-commands.
 */
final class GitNetworkCommands {

    /**
     * Abort a transfer that stays under 1 kB/s for a full minute — the common CI defaults. The
     * failure mode of a too-eager abort is a WARN plus a retry next round, never a lost commit.
     */
    private static final String[] HTTP_STALL_DETECTION = {"-c", "http.lowSpeedLimit=1000", "-c", "http.lowSpeedTime=60"
    };

    /**
     * The SSH half of the same idea: fail a connect that never completes, and notice a transport
     * that died silently within four missed fifteen-second keepalives. Documented caveat for
     * operators — this detects a dead transport, not a live sshd whose {@code git-receive-pack} is
     * wedged; that case is the deadline's.
     */
    private static final String DEFAULT_SSH_COMMAND =
            "ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=4";

    private GitNetworkCommands() {}

    /**
     * Classifies {@code args} as an invocation that talks to a remote: {@code fetch}, {@code push},
     * {@code ls-remote}, {@code clone}, {@code remote update}. Everything else — including {@code
     * remote get-url}, which only reads the local config — is local and stays unbounded (NG3).
     *
     * <p>Leading {@code -c key=value} pairs are skipped first, the same way the mutation
     * classification skips them, so a fetch carrying per-invocation config is still a fetch.
     */
    static boolean isNetwork(String... args) {
        int subcommand = subcommandIndex(args);
        if (subcommand >= args.length) {
            return false;
        }
        return switch (args[subcommand]) {
            case "fetch", "push", "ls-remote", "clone" -> true;
            case "remote" -> args.length > subcommand + 1 && args[subcommand + 1].equals("update");
            default -> false;
        };
    }

    /**
     * The index of the subcommand within {@code args}, skipping the leading {@code -c key=value}
     * global-option pairs both classifications share. May be {@code args.length} — an argv that is
     * nothing but options names no subcommand at all.
     *
     * <p>PIT M4 documented exception: {@code @DoNotMutate} on the {@code i + 1 < args.length}
     * boundary below. Mutating it to {@code i + 1 <= args.length} is provably equivalent —
     * brute-forced over every argument sequence of length 0-5 from this method's vocabulary, both
     * boundaries return the same index in every case, because a trailing {@code -c} with no
     * following value only ever pushes {@code i} past the array end, which every caller's own
     * {@code >= args.length} guard already turns into the same answer either way.
     */
    @DoNotMutate
    static int subcommandIndex(String... args) {
        int i = 0;
        while (i + 1 < args.length && args[i].equals("-c")) {
            i += 2;
        }
        return i;
    }

    /**
     * The subcommand named by {@code args}, or {@code "git"} when the argv is nothing but global
     * options and names none. Used by the runner's timeout WARN, which reports the class of command
     * that ended early without ever printing the rest of the argv — a {@code clone} or a {@code
     * push} argument can carry a credential-bearing remote URL (NFR-S2).
     */
    static String subcommand(String... args) {
        int i = subcommandIndex(args);
        return i < args.length ? args[i] : "git";
    }

    /**
     * Returns {@code args} with git's HTTP no-progress abort prefixed. The settings are global
     * options, so they must precede the subcommand; the caller's own argv — its own {@code -c}
     * pairs included — follows unchanged.
     */
    static String[] withStallDetection(String... args) {
        String[] prefixed = new String[HTTP_STALL_DETECTION.length + args.length];
        System.arraycopy(HTTP_STALL_DETECTION, 0, prefixed, 0, HTTP_STALL_DETECTION.length);
        System.arraycopy(args, 0, prefixed, HTTP_STALL_DETECTION.length, args.length);
        return prefixed;
    }

    /**
     * Gives an SSH transport its connect and keepalive limits, for this child process only and
     * only when the operator has not already set {@code GIT_SSH_COMMAND} — clobbering theirs would
     * drop the wrapper or jump host their origin is reachable through (NFR-S1).
     *
     * @param environment the child process's environment, modified in place
     */
    static void applySshStallDetection(Map<String, String> environment) {
        environment.putIfAbsent("GIT_SSH_COMMAND", DEFAULT_SSH_COMMAND);
    }
}
