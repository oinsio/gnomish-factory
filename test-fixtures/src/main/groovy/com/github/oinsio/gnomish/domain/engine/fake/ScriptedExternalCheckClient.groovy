package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck

/**
 * A scripted {@link ExternalCheckClient}: returns a queued sequence of
 * {@link PollStatus}es from {@link #poll} (one per call, in order) so a spec can
 * drive the engine's poll loop deterministically. Exposes {@link #pollCount} and —
 * when {@link #toThrow} is set — throws that exception instead. An exhausted script
 * fails loudly.
 *
 * <p>Test fake for the add-stage-engine ports; not production code, never
 * PIT-mutated.
 */
class ScriptedExternalCheckClient implements ExternalCheckClient {

    /** Statuses handed back in order; each {@code poll} call consumes the head. */
    final List<PollStatus> scripted = []

    /** Every check polled, in call order, for later assertions. */
    final List<VerifyCheck.External> calls = []

    /** When non-null, {@code poll} throws this instead of returning a status. */
    RuntimeException toThrow = null

    ScriptedExternalCheckClient(List<PollStatus> scripted = []) {
        this.scripted.addAll(scripted)
    }

    /** Number of times {@code poll} has been invoked. */
    int getPollCount() {
        calls.size()
    }

    @Override
    PollStatus poll(VerifyCheck.External check, Workspace workspace) {
        calls << check
        if (toThrow != null) {
            throw toThrow
        }
        if (scripted.isEmpty()) {
            throw new IllegalStateException('ScriptedExternalCheckClient script exhausted after ' + calls.size() + ' call(s)')
        }
        scripted.removeFirst()
    }
}
