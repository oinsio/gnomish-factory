package com.github.oinsio.gnomish.adapter.git.state;

import com.github.oinsio.gnomish.domain.engine.ExecutorVersions;
import com.github.oinsio.gnomish.untrustedtext.UntrustedExit;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Maps the task's executor version pin ({@link ExecutorVersions}) to and from {@code state.json}'s
 * {@code executorVersions} object, executor name to describe version (FR13 of
 * define-executor-contract, design D11). Written in name order so one pin always serializes to the
 * same bytes; an absent field — every tip written before the pin existed — reads as no pin, the pin
 * such a task would have had (additive under contract version 1).
 *
 * <p>An {@link UntrustedExit} like {@link StateJsonMapper}: the version is the executor's own text,
 * written verbatim to the branch document and re-minted as branch text on the way back (design D3 of
 * type-untrusted-text). The names are law names and stay strings, as stage names do in a position.
 *
 * <p>Implements FR13 of define-executor-contract.
 */
@UntrustedExit
final class StateExecutorVersionsMapper {

    private StateExecutorVersionsMapper() {}

    static Map<String, String> toDto(ExecutorVersions pin) {
        var wire = new TreeMap<String, String>();
        pin.byName().forEach((name, version) -> wire.put(name, version.raw()));
        return wire;
    }

    static ExecutorVersions fromDto(@Nullable Map<String, String> wire) {
        if (wire == null) {
            return ExecutorVersions.none();
        }
        return new ExecutorVersions(wire.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> UntrustedText.branchDocument(entry.getValue()))));
    }
}
