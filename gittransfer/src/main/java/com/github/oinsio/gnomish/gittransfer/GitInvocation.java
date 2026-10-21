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
 * <p>After the eight neutralizing pairs come the event-level hook switches,
 * {@code hook.<event>.enabled=false}, one per hook event: {@code core.hooksPath} empties the hook
 * directory but says nothing about a hook defined in the configuration by
 * {@code hook.<name>.command} and {@code hook.<name>.event}, and only the event-level switch is in
 * the factory's control (the per-name one needs a name the factory cannot know). The event list is
 * read from the githooks(5) manual page of git 2.56.0, the floor release (design D11), client and
 * server side, in the page's order; an event a later git adds is a new entry here.
 *
 * <p>Implements FR1, FR2, FR3, FR9 of own-git-invocation-policy.
 */
public final class GitInvocation {

    /** Every hook event of githooks(5) in git 2.56.0, client and server side, in the page's order. */
    private static final List<String> HOOK_EVENTS = List.of(
            "applypatch-msg",
            "pre-applypatch",
            "post-applypatch",
            "pre-commit",
            "pre-merge-commit",
            "prepare-commit-msg",
            "commit-msg",
            "post-commit",
            "pre-rebase",
            "post-checkout",
            "post-merge",
            "pre-push",
            "pre-receive",
            "update",
            "proc-receive",
            "post-receive",
            "post-update",
            "reference-transaction",
            "push-to-checkout",
            "pre-auto-gc",
            "post-rewrite",
            "sendemail-validate",
            "fsmonitor-watchman",
            "p4-changelist",
            "p4-prepare-changelist",
            "p4-post-changelist",
            "p4-pre-submit",
            "post-index-change");

    private static final List<String> NEUTRALIZING_PAIRS = List.of(
            "-c", "core.hooksPath=/dev/null",
            "-c", "commit.gpgSign=false",
            "-c", "tag.gpgSign=false",
            "-c", "core.fsmonitor=false",
            "-c", "branch.autoSetupMerge=false",
            "-c", "maintenance.auto=false",
            "-c", "gc.auto=0",
            "-c", "core.useReplaceRefs=false");

    private static final List<String> CONFIG_PAIRS = policy();

    private static final List<String> REMOVED_ENVIRONMENT = List.of("GIT_CONFIG_PARAMETERS", "GIT_CONFIG_COUNT");

    private GitInvocation() {}

    private static List<String> policy() {
        List<String> pairs = new ArrayList<>(NEUTRALIZING_PAIRS);
        for (String event : HOOK_EVENTS) {
            pairs.add("-c");
            pairs.add("hook." + event + ".enabled=false");
        }
        return List.copyOf(pairs);
    }

    /**
     * The policy's configuration pairs, flattened as git reads them: {@code -c}, {@code key=value},
     * and so on — the eight neutralizing pairs in declared order, then one event-level hook switch
     * per hook event, in the event list's order.
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
