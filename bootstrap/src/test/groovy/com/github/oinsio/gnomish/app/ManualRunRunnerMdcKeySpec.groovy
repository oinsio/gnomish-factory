package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.logtext.MdcAwareThread
import spock.lang.Specification

/**
 * The {@code taskId} MDC key is one vocabulary with two spellings: {@link
 * MdcAwareThread#TASK_ID_KEY} owns it for the log pattern and for every daemon that scopes
 * per-task work through {@code taskScope}, while {@link ManualRunRunner} repeats the literal
 * because this module reaches {@code :logtext} only transitively and the composition root taking a
 * production dependency on a leaf for one constant is the wrong trade.
 *
 * <p>A repeated literal is only acceptable while something holds the two in step, and review is
 * the wrong instrument: a divergence produces no compile error and no failing assertion anywhere
 * else — it produces a manual run whose lines are simply unreachable by the grep every other mode
 * answers to. This spec is that instrument.
 *
 * <p>Implements FR8 of harden-logging-observability.
 */
class ManualRunRunnerMdcKeySpec extends Specification {

    def "FR8: the manual run sets the same taskId key the log pattern and the daemons read"() {
        expect:
        ManualRunRunner.TASK_ID_KEY == MdcAwareThread.TASK_ID_KEY
    }
}
