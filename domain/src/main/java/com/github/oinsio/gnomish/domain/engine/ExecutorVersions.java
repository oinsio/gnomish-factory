package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The describe {@code version} of each named executor, keyed by the executor's law name: on the live
 * path, what the describe preflight obtained for this run; on the recorded side, what the task's
 * recorded rounds carry (FR13, design D11). The executor's name and model are law content, pinned by
 * the law pin at creation, so the version is the one part of an executor's identity a run can find
 * changed. The program binary itself is not pinned (proposal Q2).
 *
 * <p>An executor with no describe (an {@code agent-cli} stage) has no entry, and an absent entry is
 * never compared ({@link ExecutorVersionCheck}). The version is the executor's own free text, so it
 * stays a carrier (NFR-S2); names are law names and are held non-blank. Inert value data compared by
 * content; the map is defensively copied and unmodifiable.
 *
 * <p>Implements FR13 of define-executor-contract.
 *
 * @param byName the describe version per executor name; never null, possibly empty
 */
public record ExecutorVersions(Map<String, UntrustedText> byName) {

    private static final ExecutorVersions NONE = new ExecutorVersions(Map.of());

    public ExecutorVersions {
        byName = requireNamesAndVersions(byName);
    }

    /** No executor described: a run with no {@code program} executor, or rounds that recorded none. */
    public static ExecutorVersions none() {
        return NONE;
    }

    /** The version recorded or obtained for {@code name}, empty when that executor has none. */
    public Optional<UntrustedText> of(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /**
     * This pin with {@code ran} folded in, first version wins: an executor already pinned keeps its
     * recorded version, an executor new to the task is pinned at the version it ran with (FR13 of
     * define-executor-contract, design D11). A later difference is the pre-flight's to judge
     * ({@link ExecutorVersionCheck}), never overwritten here.
     *
     * @param ran the describe versions of the executors that ran a round; never null
     * @return the pin after that round; this pin itself when {@code ran} adds nothing
     */
    public ExecutorVersions pinFirstUse(ExecutorVersions ran) {
        var pinned = new HashMap<>(ran.byName);
        pinned.putAll(byName);
        return pinned.equals(byName) ? this : new ExecutorVersions(pinned);
    }

    /**
     * Copies the map and refuses a blank name or a blank version: an entry exists only for an
     * executor that answered describe, whose version the wire contract already holds non-blank
     * ({@code Describe}). Explicit static method so PIT's record-constructor filter does not exempt the
     * guard from the mutation gate.
     */
    private static Map<String, UntrustedText> requireNamesAndVersions(Map<String, UntrustedText> byName) {
        var copy = Map.copyOf(byName);
        copy.forEach((name, version) -> {
            if (name.isBlank() || version.isBlank()) {
                throw new IllegalArgumentException(
                        "ExecutorVersions: blank executor name or version for '" + name + "'");
            }
        });
        return copy;
    }
}
