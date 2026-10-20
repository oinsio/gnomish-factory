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
import java.util.stream.Stream;

/**
 * One factory git transfer as a value: the argument list from the leading {@code -c} pairs
 * through the refspec (the executable itself excluded), and the environment entries to set (a
 * present value) and to unset (an empty one). Built from a {@link TransferSource} and its {@link
 * Refspec}s by {@link #fetch}, {@link #harvest} and {@link #clone}, and for origin's two pushes by
 * {@link GitPush} — the only construction sites of a transfer argv in the factory (design D1, D2 of
 * own-git-transfer-argv; design D13 of tighten-spec-contract). Nothing here launches a process: the
 * git runner applies the value to a {@code ProcessBuilder}, the seed helper renders it.
 *
 * <p>Every argv opens with the invocation policy, {@link GitInvocation#configPairs()}; the
 * compact constructor refuses one that does not (design D2 of own-git-invocation-policy). The
 * transfer's own set follows, names no policy key again, so no key carries two values (FR4), and
 * is deny-by-default in two halves, because {@code fetch} and {@code clone} do not parse the same
 * options. <em>Everywhere</em>: {@code --no-tags} (tag auto-following writes into {@code
 * refs/tags/} even on a fetch by SHA), {@code --no-recurse-submodules} with
 * {@code fetch.recurseSubmodules=no} and {@code submodule.recurse=false} (a populated submodule
 * would trigger a second, unnamed network fetch), {@code fetch.prune=false} (an operator's prune
 * setting deletes the destination of a short-name source), {@code fetch.fsckObjects} and {@code
 * transfer.fsckObjects} (every received object is validated, FR5) with the three legacy message
 * ids Gitaly ignores build-wide downgraded to {@code ignore}, and {@code --end-of-options} before
 * the source and the refspec (a refspec whose first character is {@code -} is a refspec). The
 * {@code -c} pairs precede the subcommand so they configure the process rather than the new
 * repository. <em>Fetch only</em>: {@code --no-write-fetch-head} ({@code clone} rejects it, and
 * writes no {@code FETCH_HEAD} anyway) and, for the named remote alone, {@code --refmap=} (a fetch
 * with an explicit destination still applies the configured refspecs as a mapping; a URL source
 * has none). No {@code --depth}: full history, because resume must resolve it (NFR-P1).
 *
 * <p>The environment removes the policy's {@link GitInvocation#removedEnvironment()} (inherited
 * per-process configuration) and every repository override (the object-directory, alternates,
 * work-tree and index variables), sets {@code GIT_ALLOW_PROTOCOL} to the source's allowlist — the
 * whole protocol policy, so no {@code protocol.*.allow} key and no {@code GIT_PROTOCOL_FROM_USER}
 * travels beside it (FR4) — and appends the source's configuration-isolation shape (FR6).
 *
 * <p>Implements FR1, FR3, FR4, FR5, FR6, FR7 of own-git-transfer-argv; FR4 of
 * own-git-invocation-policy.
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
            "fetch.fsckObjects=true",
            "transfer.fsckObjects=true",
            "fetch.fsck.badTimezone=ignore",
            "fetch.fsck.missingSpaceBeforeDate=ignore",
            "fetch.fsck.zeroPaddedFilemode=ignore");

    /** Inherited variables that would redirect any transfer; unset after the policy's removals. */
    private static final List<String> STRIPPED =
            List.of("GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_WORK_TREE", "GIT_INDEX_FILE");

    /** Package-private: the push factories of {@link GitPush} place the same terminator. */
    static final String END_OF_OPTIONS = "--end-of-options";

    /** Refuses an argv that does not open with {@link GitInvocation#configPairs()} (design D2). */
    public GitTransfer {
        argv = List.copyOf(argv);
        if (Collections.indexOfSubList(argv, GitInvocation.configPairs()) != 0) {
            throw new IllegalArgumentException(
                    "a transfer argv opens with the invocation policy's pairs " + GitInvocation.configPairs());
        }
        environment = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(environment));
    }

    /**
     * A {@code git fetch} of exactly {@code refspec} — and of each of {@code more}, in one transfer,
     * so every ref it moves reflects one advertisement of the source — from {@code origin} into the
     * clone the runner executes it in. A touchpoint fetch names the task branch and the factory
     * register together this way (FR13 of tighten-spec-contract). A fetch of more than one refspec
     * passes {@code --atomic}: the branch refspec is unforced and can be rejected as a
     * non-fast-forward while a forced register lands, and {@code --atomic} makes every ref of the
     * transfer land together or none (design D13 of tighten-spec-contract, as amended 2026-10-13).
     * A fetch of one refspec has nothing to pair and keeps the plain form.
     *
     * @param source the named remote; never null
     * @param refspec the first refspec to move; never null
     * @param more further refspecs moved by the same transfer, in order; none null
     * @return the transfer value
     */
    public static GitTransfer fetch(Origin source, Refspec refspec, Refspec... more) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(refspec, "refspec");
        List<Refspec> refspecs = new ArrayList<>(List.of(more));
        refspecs.addFirst(refspec);
        return fetchFrom(source, refspecs, refspecs.size() > 1);
    }

    /**
     * A harvest: a {@code git fetch} of the box's task branch over the container transport into the
     * task's quarantine and nowhere else (design D18 of tighten-spec-contract). The destination is a
     * {@link QuarantineRef}, so no harvest built here can name the task branch: the branch moves only
     * through the git adapter's admission, after it inspected what landed.
     *
     * @param source the container transport; never null
     * @param boxBranch the task branch as the box names it; never null
     * @param quarantine the task's quarantine; never null
     * @return the transfer value
     */
    public static GitTransfer harvest(Container source, String boxBranch, QuarantineRef quarantine) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(quarantine, "quarantine");
        return fetchFrom(source, List.of(quarantine.harvestRefspec(boxBranch)), false);
    }

    private static GitTransfer fetchFrom(FetchSource source, List<Refspec> refspecs, boolean atomic) {
        List<String> argv = configPairs();
        argv.add("fetch");
        argv.add("--no-tags");
        argv.add("--no-recurse-submodules");
        argv.add("--no-write-fetch-head");
        if (atomic) {
            argv.add("--atomic");
        }
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
        refspecs.forEach(each -> argv.add(each.value()));
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

    /** The invocation policy's pairs, then the transfer's own: everything before the subcommand. */
    private static List<String> configPairs() {
        List<String> argv = new ArrayList<>(GitInvocation.configPairs());
        for (String pair : CONFIG_KEYS) {
            argv.add("-c");
            argv.add(pair);
        }
        return argv;
    }

    /** Package-private: a push to origin runs under origin's environment, built here once. */
    static SequencedMap<String, Optional<String>> environment(TransferSource source) {
        SequencedMap<String, Optional<String>> environment = new LinkedHashMap<>();
        Stream.concat(GitInvocation.removedEnvironment().stream(), STRIPPED.stream())
                .forEach(variable -> environment.put(variable, Optional.empty()));
        environment.put("GIT_ALLOW_PROTOCOL", Optional.of(source.protocolAllowlist()));
        environment.putAll(source.configurationIsolation());
        return environment;
    }
}
