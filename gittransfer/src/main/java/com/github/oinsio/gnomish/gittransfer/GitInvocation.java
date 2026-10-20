package com.github.oinsio.gnomish.gittransfer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The factory's one git invocation policy: the command-scope {@code -c} pairs every factory git
 * process carries before its subcommand, and the inherited environment variables every factory git
 * process has removed (design D1, D3 of own-git-invocation-policy). It also builds the whole command
 * line both git launchers hand to {@code ProcessBuilder} (design D2), so a launcher cannot run git
 * without the policy.
 *
 * <p>Why: a clone's {@code .git/config} is untrusted input — whoever can write it can name hooks, a
 * filesystem monitor, signing programs or replacement refs that git would run or honour. Command
 * scope ({@code git -c}) outranks every configuration file, so these pairs neutralize those keys
 * whatever the clone says, and removing {@code GIT_CONFIG_PARAMETERS} / {@code GIT_CONFIG_COUNT}
 * stops an inherited per-process configuration from adding keys back. Credential, SSH and URL
 * rewrite keys stay ambient (NFR-S2).
 *
 * <p>Implements FR1, FR2, FR3 of own-git-invocation-policy.
 */
public final class GitInvocation {

    private static final List<String> CONFIG_PAIRS = List.of(
            "-c", "core.hooksPath=/dev/null",
            "-c", "commit.gpgSign=false",
            "-c", "tag.gpgSign=false",
            "-c", "core.fsmonitor=false",
            "-c", "branch.autoSetupMerge=false",
            "-c", "maintenance.auto=false",
            "-c", "gc.auto=0",
            "-c", "core.useReplaceRefs=false");

    private static final List<String> REMOVED_ENVIRONMENT = List.of("GIT_CONFIG_PARAMETERS", "GIT_CONFIG_COUNT");

    private GitInvocation() {}

    /**
     * The policy's configuration pairs, flattened as git reads them: {@code -c}, {@code key=value},
     * and so on, in declared order.
     *
     * @return the pairs; read-only
     */
    public static List<String> configPairs() {
        return CONFIG_PAIRS;
    }

    /**
     * The environment variables removed from every factory git process.
     *
     * @return the variable names, in declared order; read-only
     */
    public static List<String> removedEnvironment() {
        return REMOVED_ENVIRONMENT;
    }

    /**
     * The whole command line: {@code gitBinary}, then the policy pairs, then {@code args}
     * unchanged. An {@code args} that already opens with the pairs (every {@code GitTransfer.argv()}
     * does) gets no second copy.
     *
     * @param gitBinary the git executable; never blank
     * @param args the caller's arguments; never null
     * @return the command line; read-only
     * @throws IllegalArgumentException when {@code gitBinary} is blank
     */
    public static List<String> commandLine(String gitBinary, List<String> args) {
        if (gitBinary.isBlank()) {
            throw new IllegalArgumentException("git binary must not be blank");
        }
        List<String> line = new ArrayList<>();
        line.add(gitBinary);
        if (Collections.indexOfSubList(args, CONFIG_PAIRS) != 0) {
            line.addAll(CONFIG_PAIRS);
        }
        line.addAll(args);
        return List.copyOf(line);
    }
}
