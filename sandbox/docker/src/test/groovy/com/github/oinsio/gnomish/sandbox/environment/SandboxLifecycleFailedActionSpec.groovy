package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory

/**
 * NFR-R1 of add-serve-sandbox-lifecycle: a destructive action that docker rejected leaves the
 * object exactly where it was — reporting it as stopped or disposed would make the verdict and
 * the ledger lie to the operator. Every action path reports the failure as skipped-no-verdict,
 * carrying the reason it was attempting.
 *
 * <p>FR4, NFR-R1 of add-serve-sandbox-lifecycle.
 */
class SandboxLifecycleFailedActionSpec extends SandboxLifecycleDecisionSpecBase {

    def setup() {
        // Every docker action fails, and the object is still there when the existence probe asks.
        docker.onRun = { List<String> args ->
            existenceProbe(args)
            ? new DockerResult(0, 'still-here', '')
            : new DockerResult(1, '', 'Error response from daemon: nope')
        }
    }

    def "a failed stop of an unowned running main box is reported as skipped, never as stopped"() {
        when:
        decision.decideContainer(
                obj('gnomish-box-x'), cls('x', ObjectRole.MAIN_BOX, OwnershipMode.TRACKED), running(), UNOWNED, NOW,
                THRESHOLDS)

        then:
        verdicts[0].category() == SweepVerdictCategory.SKIPPED_NO_VERDICT
        verdicts[0].reason() == 'stop failed: unowned running main-box'
    }

    def "a failed removal of an unowned guard is reported as skipped, never as disposed"() {
        when:
        decision.decideContainer(
                obj('gnomish-guard-x'), cls('x', ObjectRole.GUARD, OwnershipMode.TRACKED), running(), UNOWNED, NOW,
                THRESHOLDS)

        then:
        verdicts[0].category() == SweepVerdictCategory.SKIPPED_NO_VERDICT
        verdicts[0].reason() == 'dispose failed: unowned guard'
    }

    def "a key-triple dispose that leaves the object in place is reported as skipped"() {
        when:
        decision.decideRemnant(
                volume('gnomish-vol-x'), cls('x', ObjectRole.MAIN_BOX, OwnershipMode.TRACKED), OLD, UNOWNED, NOW,
                THRESHOLDS)

        then:
        verdicts[0].category() == SweepVerdictCategory.SKIPPED_NO_VERDICT
        verdicts[0].reason() == 'dispose failed: unowned remnant, past reap threshold'
    }

    // NFR-R3: the key-triple path goes through the best-effort disposal PORT, which reports
    // nothing and may throw when the runtime is unreachable. A throw there must not abort the
    // pass mid-way — the remaining objects still need verdicts — and must not be reported as a
    // disposal either: the outcome is read back off the object, which is still there.
    def "a disposal port that throws is absorbed, and the surviving object is still reported as skipped"() {
        given:
        disposal.dispose(_) >> {
            throw new DockerUnavailableException('daemon gone', null)
        }

        when:
        decision.decideRemnant(
                volume('gnomish-vol-x'), cls('x', ObjectRole.MAIN_BOX, OwnershipMode.TRACKED), OLD, UNOWNED, NOW,
                THRESHOLDS)

        then:
        noExceptionThrown()
        verdicts[0].category() == SweepVerdictCategory.SKIPPED_NO_VERDICT
        verdicts[0].reason() == 'dispose failed: unowned remnant, past reap threshold'
    }

    // The same throw on an object the runtime nevertheless removed: the verdict follows the object,
    // not the exception — a dispose that took is reported as a disposal even when the port blew up.
    def "a disposal port that throws after the object is gone still reports the disposal"() {
        given:
        disposal.dispose(_) >> {
            throw new DockerUnavailableException('daemon gone mid-dispose', null)
        }
        // The existence probe answers "gone" — the triple really was removed before the failure.
        docker.onRun = { List<String> args ->
            existenceProbe(args) ? new DockerResult(1, '', 'No such object') : new DockerResult(0, '', '')
        }

        when:
        decision.decideRemnant(
                volume('gnomish-vol-x'), cls('x', ObjectRole.MAIN_BOX, OwnershipMode.TRACKED), OLD, UNOWNED, NOW,
                THRESHOLDS)

        then:
        verdicts[0].category() == SweepVerdictCategory.DISPOSED_AGED
    }

    def "a failed removal of an aged unrecognized object is reported as skipped"() {
        when:
        decision.decideRemnant(
                network('mystery-net'), cls('x', ObjectRole.UNRECOGNIZED, OwnershipMode.TRACKED), OLD, UNOWNED, NOW,
                THRESHOLDS)

        then:
        verdicts[0].category() == SweepVerdictCategory.SKIPPED_NO_VERDICT
        verdicts[0].reason() == 'dispose failed: unowned remnant, past reap threshold'
    }
}
