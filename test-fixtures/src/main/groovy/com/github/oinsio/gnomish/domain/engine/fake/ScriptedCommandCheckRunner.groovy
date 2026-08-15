package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.port.CommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck

/**
 * A scripted {@link CommandCheckRunner}: returns queued {@link Verdict}s in order
 * (or a single fixed verdict), records each call, and — when {@link #toThrow} is
 * set — throws that exception instead (for adapter-throws → CannotVerify tests).
 * When {@link #onRun} is set it runs that side effect mid-call (e.g. to advance a
 * {@link VirtualClock} so a spec can prove a non-zero measured duration).
 * An exhausted script fails loudly.
 *
 * <p>Test fake for the add-stage-engine ports; not production code, never
 * PIT-mutated.
 */
class ScriptedCommandCheckRunner implements CommandCheckRunner {

    /** Verdicts handed back in order; each {@code run} call consumes the head. */
    final List<Verdict> scripted = []

    /** Every check received, in call order, for later assertions. */
    final List<VerifyCheck.Command> calls = []

    /** When non-null, {@code run} throws this instead of returning a verdict. */
    RuntimeException toThrow = null

    /** When non-null, invoked inside {@code run} (before returning) — e.g. to advance a clock. */
    Closure onRun = null

    ScriptedCommandCheckRunner(List<Verdict> scripted = []) {
        this.scripted.addAll(scripted)
    }

    /** Convenience: always return the same fixed verdict. */
    static ScriptedCommandCheckRunner fixed(Verdict verdict) {
        new ScriptedCommandCheckRunner([verdict])
    }

    @Override
    Verdict run(VerifyCheck.Command check, Workspace workspace) {
        calls << check
        if (toThrow != null) {
            throw toThrow
        }
        if (onRun != null) {
            onRun.call(check, workspace)
        }
        if (scripted.isEmpty()) {
            throw new IllegalStateException('ScriptedCommandCheckRunner script exhausted after ' + calls.size() + ' call(s)')
        }
        scripted.removeFirst()
    }
}
