package com.github.oinsio.gnomish.gittransfer;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * Where a factory git transfer reads from — one of a closed set of kinds, each carrying the two
 * per-source facts of the transfer policy as values: its protocol allowlist and its
 * configuration-isolation shape (design D2 of own-git-transfer-argv). The owner ({@link
 * GitTransfer}) reads both from the source; a caller chooses a kind and can vary nothing else.
 *
 * <p>The kinds split by subcommand: {@link FetchSource} — {@link Origin} and {@link Container} —
 * are read by {@code git fetch} into an existing clone, {@link SeedPath} is read by {@code git
 * clone} into a new one. The split is a type rather than a runtime check so that a clone value can
 * never carry a fetch-only flag and a fetch can never be built from a seed path: the compiler,
 * not a guard, keeps the two halves of the common set apart (design D2's "two halves").
 *
 * <p>A fourth source (a VM, a remote runner) is a new permitted record here and a row in ADR
 * 0008 — never a flag list at a call site (M4).
 *
 * <p>Implements FR1, FR4, FR6 of own-git-transfer-argv.
 */
public sealed interface TransferSource permits TransferSource.FetchSource, TransferSource.SeedPath {

    /** The trusted {@code origin} remote, as one value. */
    Origin ORIGIN = new Origin();

    /** The value git's own configuration reads as "no file here" — a path that is never a file. */
    String NOWHERE = "/dev/null";

    /**
     * The closed protocol allowlist for this source, in {@code GIT_ALLOW_PROTOCOL}'s colon-separated
     * notation: every listed protocol is always allowed, every other never, overriding every
     * configuration scope (FR4).
     */
    String protocolAllowlist();

    /**
     * The configuration-scope environment this source runs under, keyed by variable name; a
     * present value is set, an empty one unset. Insertion-ordered so a renderer emits it
     * deterministically. Empty means "keep the operator's configuration" (FR6).
     */
    SequencedMap<String, Optional<String>> configurationIsolation();

    /** A source {@code git fetch} reads from: the transfer lands in an existing clone. */
    sealed interface FetchSource extends TransferSource permits TransferSource.Origin, TransferSource.Container {}

    /**
     * The operator's {@code origin}. It keeps the operator's global configuration — credential
     * helpers, URL rewrites and {@code core.sshCommand} are how the operator authenticates (design
     * D3) — while the owner re-asserts every key the common set depends on by {@code -c}, which
     * wins over every scope. Allowlist {@code https:http:ssh:file}; {@code file} is what the
     * test fixtures' bare-path origins use, {@code http} is an internal server (the Gitea lane)
     * authenticating through the same ambient credentials as {@code https} (proposal Q3),
     * {@code git://} is deliberately absent (proposal Q2), and {@code ext} being absent is what
     * keeps a rewritten {@code ext::} URL refused (FR4).
     */
    record Origin() implements FetchSource {

        @Override
        public String protocolAllowlist() {
            return "https:http:ssh:file";
        }

        @Override
        public SequencedMap<String, Optional<String>> configurationIsolation() {
            return Collections.emptySortedMap();
        }
    }

    /**
     * A task container reached over git's {@code ext::} transport — untrusted content on the far
     * end, so it reads no operator configuration at all: global and system files and the XDG home
     * all point at nowhere, which is also what keeps every credential helper out of the box's reach
     * (NFR-S3). Allowlist exactly {@code ext}: listing it is what enables the helper, so no {@code
     * protocol.ext.allow} key travels beside it (design D2).
     *
     * @param extUrl the full transport URL, {@code ext::<command> %S}; the caller assembles it
     */
    record Container(String extUrl) implements FetchSource {

        /** The transport prefix git dispatches to the {@code ext} remote helper on. */
        static final String EXT_PREFIX = "ext::";

        public Container {
            Objects.requireNonNull(extUrl, "extUrl");
            if (!extUrl.startsWith(EXT_PREFIX)
                    || extUrl.substring(EXT_PREFIX.length()).isBlank()) {
                throw new IllegalArgumentException(
                        "a container source is an ext:: transport URL with a command after the"
                                + " prefix; the allowlist admits nothing else");
            }
        }

        @Override
        public String protocolAllowlist() {
            return "ext";
        }

        @Override
        public SequencedMap<String, Optional<String>> configurationIsolation() {
            return isolated(NOWHERE);
        }
    }

    /**
     * The operator's clone as the seed of a container's working copy, read by {@code git clone} in
     * the seed helper. Reads no operator configuration either, with one exception the helper needs:
     * its global file is the throwaway {@link #SAFE_DIRECTORY_CONFIG}, which holds the {@code
     * safe.directory} entries git honours from global scope only and nothing else (design D6). The
     * leaf names the path so the script's own export and the owner's environment cannot disagree.
     * Allowlist exactly {@code file}. No branch: the seed script binds it to its first positional
     * parameter, and the owner's argv carries {@link GitTransfer#BRANCH_PARAMETER} in its place.
     *
     * @param source the factory clone as mounted in the helper
     * @param destination the working copy to create; must differ from the source
     */
    record SeedPath(Path source, Path destination) implements TransferSource {

        /** The seed helper's throwaway global configuration file — {@code safe.directory} only. */
        public static final String SAFE_DIRECTORY_CONFIG = "/tmp/gnomish-seed-gitconfig";

        public SeedPath {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(destination, "destination");
            if (source.equals(destination)) {
                throw new IllegalArgumentException("a seed clone's destination must differ from its source: " + source);
            }
        }

        @Override
        public String protocolAllowlist() {
            return "file";
        }

        @Override
        public SequencedMap<String, Optional<String>> configurationIsolation() {
            return isolated(SAFE_DIRECTORY_CONFIG);
        }
    }

    /**
     * The full-isolation shape: system file and XDG home at nowhere, the global file wherever the
     * source says — nowhere for a container, the helper's throwaway file for a seed.
     */
    private static SequencedMap<String, Optional<String>> isolated(String globalFile) {
        SequencedMap<String, Optional<String>> shape = new LinkedHashMap<>();
        shape.put("GIT_CONFIG_GLOBAL", Optional.of(globalFile));
        shape.put("GIT_CONFIG_SYSTEM", Optional.of(NOWHERE));
        shape.put("XDG_CONFIG_HOME", Optional.of(NOWHERE));
        return Collections.unmodifiableSequencedMap(shape);
    }
}
