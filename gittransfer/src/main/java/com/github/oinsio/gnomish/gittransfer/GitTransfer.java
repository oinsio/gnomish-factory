package com.github.oinsio.gnomish.gittransfer;

import com.github.oinsio.gnomish.gittransfer.TransferSource.Container;
import com.github.oinsio.gnomish.gittransfer.TransferSource.FetchSource;
import com.github.oinsio.gnomish.gittransfer.TransferSource.Origin;
import com.github.oinsio.gnomish.gittransfer.TransferSource.SeedPath;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * One factory git transfer as a value: the argument list from the leading {@code -c} pairs
 * through the refspec (the executable itself excluded), and the environment entries to set (a
 * present value) and to unset (an empty one). Built from exactly two inputs — a {@link
 * TransferSource} and one {@link Refspec} — by {@link #fetch} and {@link #clone}, the only
 * construction sites of a transfer argv in the factory (design D1, D2 of own-git-transfer-argv).
 * Nothing here launches a process: the git runner applies the value to a {@code ProcessBuilder},
 * the seed helper renders it into its script.
 *
 * <p>The common set is deny-by-default and comes in two halves, because {@code fetch} and {@code
 * clone} do not parse the same options. <em>Everywhere</em>: {@code --no-tags} (tag auto-following
 * writes into {@code refs/tags/} even on a fetch by SHA), {@code --no-recurse-submodules} with
 * {@code fetch.recurseSubmodules=no} and {@code submodule.recurse=false} (a populated submodule
 * would trigger a second, unnamed network fetch), {@code fetch.prune=false} (an operator's prune
 * setting deletes the destination of a short-name source), {@code maintenance.auto=false} and
 * {@code gc.auto=0} (no background work in the clone), {@code fetch.fsckObjects} and {@code
 * transfer.fsckObjects} (every received object is validated, FR5) with the three legacy message
 * ids Gitaly ignores build-wide downgraded to {@code ignore}, and {@code --end-of-options} before
 * the source and the refspec (a refspec whose first character is {@code -} is a refspec). The
 * {@code -c} pairs precede the subcommand so they configure the process rather than the new
 * repository. <em>Fetch only</em>: {@code --no-write-fetch-head} ({@code clone} rejects it, and
 * writes no {@code FETCH_HEAD} anyway) and, for the named remote alone, {@code --refmap=} (a fetch
 * with an explicit destination still applies the configured refspecs as a mapping; a URL source
 * has none). No {@code --depth}: full history, because resume must resolve it (NFR-P1).
 *
 * <p>The environment strips every inherited per-process configuration and repository override
 * ({@code GIT_CONFIG_PARAMETERS}, {@code GIT_CONFIG_COUNT}, the object-directory, alternates,
 * work-tree and index variables), sets {@code GIT_ALLOW_PROTOCOL} to the source's allowlist — the
 * whole protocol policy, so no {@code protocol.*.allow} key and no {@code GIT_PROTOCOL_FROM_USER}
 * travels beside it (FR4) — and appends the source's configuration-isolation shape (FR6).
 *
 * <p>Implements FR1, FR3, FR4, FR5, FR6, FR7 of own-git-transfer-argv.
 *
 * @param argv the git arguments, executable excluded; read-only
 * @param environment variables to set (present) or unset (empty), in emission order; read-only
 */
public record GitTransfer(List<String> argv, SequencedMap<String, Optional<String>> environment) {

    /**
     * The one argv element of a {@link #clone} the caller substitutes for the branch: a real branch
     * factory-side, the shell word {@code "$1"} in the seed helper's constant script (design D6). It
     * contains {@code :}, which no ref name may, so a value that leaks unsubstituted is refused by
     * git rather than cloned.
     */
    public static final String BRANCH_PARAMETER = "gnomish:seed-branch";

    /** The everywhere half of the common set that travels as {@code -c key=value} pairs. */
    private static final List<String> CONFIG_KEYS = List.of(
            "fetch.recurseSubmodules=no",
            "submodule.recurse=false",
            "fetch.prune=false",
            "maintenance.auto=false",
            "gc.auto=0",
            "fetch.fsckObjects=true",
            "transfer.fsckObjects=true",
            "fetch.fsck.badTimezone=ignore",
            "fetch.fsck.missingSpaceBeforeDate=ignore",
            "fetch.fsck.zeroPaddedFilemode=ignore");

    /** Inherited variables that would redirect or reconfigure any transfer; unset on every kind. */
    private static final List<String> STRIPPED = List.of(
            "GIT_CONFIG_PARAMETERS",
            "GIT_CONFIG_COUNT",
            "GIT_OBJECT_DIRECTORY",
            "GIT_ALTERNATE_OBJECT_DIRECTORIES",
            "GIT_WORK_TREE",
            "GIT_INDEX_FILE");

    private static final String END_OF_OPTIONS = "--end-of-options";

    public GitTransfer {
        argv = List.copyOf(argv);
        environment = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(environment));
    }

    /**
     * A {@code git fetch} of exactly {@code refspec} from {@code source} into the clone the runner
     * executes it in.
     *
     * @param source the named remote or the container transport; never null
     * @param refspec the one refspec to move; never null
     * @return the transfer value
     */
    public static GitTransfer fetch(FetchSource source, Refspec refspec) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(refspec, "refspec");
        List<String> argv = configPairs();
        argv.add("fetch");
        argv.add("--no-tags");
        argv.add("--no-recurse-submodules");
        argv.add("--no-write-fetch-head");
        String remote =
                switch (source) {
                    case Origin _ -> {
                        argv.add("--refmap=");
                        yield "origin";
                    }
                    case Container container -> container.extUrl();
                };
        argv.add(END_OF_OPTIONS);
        argv.add(remote);
        argv.add(refspec.value());
        return new GitTransfer(argv, environment(source));
    }

    /**
     * A {@code git clone} of one branch — {@link #BRANCH_PARAMETER} until the caller substitutes
     * it — from the seed's source path into its destination, over git's transport path rather than
     * its local-path mode (no hardlinks, no object-file copy, validated objects; FR7).
     *
     * @param seed the source and destination paths; never null
     * @return the transfer value
     */
    public static GitTransfer clone(SeedPath seed) {
        Objects.requireNonNull(seed, "seed");
        List<String> argv = configPairs();
        argv.add("clone");
        argv.add("--no-local");
        argv.add("--no-hardlinks");
        argv.add("--single-branch");
        argv.add("--no-tags");
        argv.add("--no-recurse-submodules");
        argv.add("--branch");
        argv.add(BRANCH_PARAMETER);
        argv.add(END_OF_OPTIONS);
        argv.add(seed.source().toString());
        argv.add(seed.destination().toString());
        return new GitTransfer(argv, environment(seed));
    }

    private static List<String> configPairs() {
        List<String> argv = new ArrayList<>();
        for (String pair : CONFIG_KEYS) {
            argv.add("-c");
            argv.add(pair);
        }
        return argv;
    }

    private static SequencedMap<String, Optional<String>> environment(TransferSource source) {
        SequencedMap<String, Optional<String>> environment = new LinkedHashMap<>();
        for (String variable : STRIPPED) {
            environment.put(variable, Optional.empty());
        }
        environment.put("GIT_ALLOW_PROTOCOL", Optional.of(source.protocolAllowlist()));
        environment.putAll(source.configurationIsolation());
        return environment;
    }
}
