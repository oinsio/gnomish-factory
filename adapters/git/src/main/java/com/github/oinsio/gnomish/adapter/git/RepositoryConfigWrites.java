package com.github.oinsio.gnomish.adapter.git;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Which argv writes a repository's configuration: {@code config} in any form but a read, and
 * {@code remote} in a write form. The runner's untyped entry refuses these before any process
 * launches, because the factory writes nothing to a clone's configuration — what a factory git
 * process needs rides in on the invocation policy, per invocation (design D4 of
 * own-git-invocation-policy).
 *
 * <p>The classification reads the caller's arguments, skipping the leading {@code -c} pairs with
 * the same {@link GitNetworkCommands#subcommandIndex} every other classification of the runner
 * uses, so a configuration write behind per-invocation options is still a write.
 *
 * <p>Implements FR5, NFR-O1 of own-git-invocation-policy.
 */
final class RepositoryConfigWrites {

    /** The option forms of {@code git config} that only read. */
    private static final Set<String> CONFIG_READ_OPTIONS =
            Set.of("--get", "--get-all", "--get-regexp", "--get-urlmatch", "--list", "-l");

    /** The subcommand forms of {@code git config} (git 2.46 and later) that only read. */
    private static final Set<String> CONFIG_READ_SUBCOMMANDS = Set.of("get", "list");

    /** The {@code git remote} subcommands that write the repository's configuration. */
    private static final Set<String> REMOTE_WRITES =
            Set.of("add", "rename", "remove", "rm", "set-head", "set-branches", "set-url");

    private RepositoryConfigWrites() {}

    /**
     * The subcommand {@code args} would write the repository's configuration with — {@code config}
     * or {@code remote} — or empty when the argv is a read or no configuration command at all.
     *
     * @param args the caller's argv, possibly opening with {@code -c key=value} pairs
     * @return the refused subcommand, or empty when the argv may run
     */
    static Optional<String> refusedSubcommand(String... args) {
        int subcommand = GitNetworkCommands.subcommandIndex(args);
        if (subcommand >= args.length) {
            return Optional.empty();
        }
        List<String> rest = Arrays.asList(args).subList(subcommand + 1, args.length);
        boolean write =
                switch (args[subcommand]) {
                    case "config" -> !isConfigRead(rest);
                    case "remote" -> !rest.isEmpty() && REMOTE_WRITES.contains(rest.getFirst());
                    default -> false;
                };
        return write ? Optional.of(args[subcommand]) : Optional.empty();
    }

    private static boolean isConfigRead(List<String> rest) {
        if (!rest.isEmpty() && CONFIG_READ_SUBCOMMANDS.contains(rest.getFirst())) {
            return true;
        }
        return rest.stream().anyMatch(CONFIG_READ_OPTIONS::contains);
    }
}
