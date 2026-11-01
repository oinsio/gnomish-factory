package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The one comparison of an executor's recorded describe version with the version a later run's
 * describe preflight obtained (FR13, design D11): pure, no port, no log. An executor the recorded
 * rounds carry no version for is in its first use and is not compared; an executor the current run
 * did not describe is not compared either. Two present versions that differ are a {@link
 * EscalationReport.PipelineMismatch} naming the executor (a law name, minted as manifest text) and
 * both versions, which the engine pre-flight escalates before any round runs.
 *
 * <p>Versions compare by content: the recorded one is read back off the task branch and the current
 * one off the executor's describe output, so their provenances differ by construction and never
 * decide. When several executors differ, the first by name is reported, so the report is
 * deterministic.
 *
 * <p>Implements FR13 of define-executor-contract.
 */
public final class ExecutorVersionCheck {

    private ExecutorVersionCheck() {}

    /**
     * Compares {@code recorded} with {@code current} executor by executor.
     *
     * @param recorded the versions the task's recorded rounds carry; never null
     * @param current the versions this run's describe preflight obtained; never null
     * @return the mismatch naming the first differing executor by name and both versions, or empty
     *     when every shared executor agrees
     */
    public static Optional<EscalationReport.PipelineMismatch> compare(
            ExecutorVersions recorded, ExecutorVersions current) {
        for (var entry : new TreeMap<>(current.byName()).entrySet()) {
            var was = recorded.of(entry.getKey());
            if (was.isPresent() && !was.get().equals(entry.getValue())) {
                return Optional.of(new EscalationReport.PipelineMismatch(new MismatchCause.ExecutorVersion(
                        UntrustedText.manifest(entry.getKey()), was.get(), entry.getValue())));
            }
        }
        return Optional.empty();
    }
}
