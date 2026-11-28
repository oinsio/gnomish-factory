package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.agent.TerminalClose
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.executorcontract.Executor
import groovy.transform.CompileStatic

/**
 * The detector fixtures of the round's owner gates (task 6.8 of extract-agent-round-runner, design
 * D8): each makes a reserved call from outside its owner. Statically compiled, so each call is an
 * invocation of the typed target in the bytecode, as a Java caller's would be; they are imported
 * alone by the detector features and never by a production scan.
 */
final class OwnerGateIntruders {

    private OwnerGateIntruders() {
    }

    /** Polls the stage port outside {@code PendingPoll}. */
    @CompileStatic
    static final class StagePolling {
        static Object poll(StageExecutor executor, StageExecutor.Request request, PendingTurn turn) {
            executor.poll(request, turn)
        }
    }

    /** Polls the contract outside the turn dispatch. */
    @CompileStatic
    static final class ContractPolling {
        static Object poll(Executor executor) {
            executor.poll(null, null)
        }
    }

    /** Opens and closes a round outside the stage wrapper. */
    @CompileStatic
    static final class Bracketing {
        static void bracket(RoundEnvironmentSource source, StageExecutor.Request request, TerminalClose close) {
            source.openRound(request).closeRound(close)
        }
    }
}
