package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.subprocess.ProcessGroupSweep.Answer
import com.github.oinsio.gnomish.subprocess.ProcessGroupSweep.GroupSignal

/**
 * The group sweep's signal for a spec (design D19 of fix-terminal-receipt-convergence): it records
 * every signal as {@code "<signal> <group>"}, and either answers each probe from a script — the
 * {@code kill} rounds get no answer of their own — or hands every signal to the owner's real {@code
 * kill}, so a spec can observe what the real sweep sent, and when.
 */
final class ProcessProbes implements GroupSignal {

    private final List<Answer> probes
    private final GroupSignal real
    private final List<String> sent
    private final Closure<?> beforeFirst

    /** Answers the probes from {@code probes}, in order; a KILL round is answered as accepted. */
    ProcessProbes(List<Answer> probes, List<String> sent) {
        this.probes = new ArrayList<>(probes)
        this.real = null
        this.sent = sent
        this.beforeFirst = {}
    }

    /** Hands every signal to {@code real}, running {@code beforeFirst} once before the first. */
    ProcessProbes(GroupSignal real, List<String> sent, Closure<?> beforeFirst = {}) {
        this.probes = null
        this.real = real
        this.sent = sent
        this.beforeFirst = beforeFirst
    }

    @Override
    Answer send(String signal, long group) {
        if (sent.isEmpty()) {
            beforeFirst.call()
        }
        sent << "${signal} ${group}".toString()
        if (real != null) {
            return real.send(signal, group)
        }
        signal == '0' ? probes.removeAt(0) : Answer.ANSWERED
    }
}
