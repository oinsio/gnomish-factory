package com.github.oinsio.gnomish.app.port.tracker

import spock.lang.Specification

/**
 * StateLabels: the raw label-presence facts an adapter reports, including the combinations only a
 * kill window produces — a task wearing both the ready and the working label.
 *
 * FR19 of harden-task-branch-contract.
 */
class StateLabelsSpec extends Specification {

    def "each single-label factory reports exactly its own label, on an open task"() {
        expect:
        StateLabels.readyOnly() == new StateLabels(true, false, false, false, false)
        StateLabels.workingOnly() == new StateLabels(false, true, false, false, false)
        StateLabels.needsHumanOnly() == new StateLabels(false, false, true, false, false)
        StateLabels.deliveredOnly() == new StateLabels(false, false, false, true, false)
    }

    def "each single-label factory reports its own label present and every other absent"() {
        expect:
        StateLabels.readyOnly().ready()
        !StateLabels.readyOnly().working()

        and:
        StateLabels.workingOnly().working()
        !StateLabels.workingOnly().needsHuman()

        and:
        StateLabels.needsHumanOnly().needsHuman()
        !StateLabels.needsHumanOnly().delivered()

        and:
        StateLabels.deliveredOnly().delivered()
        !StateLabels.deliveredOnly().ready()

        and: 'none of them describes a closed task'
        !StateLabels.readyOnly().closed()
        !StateLabels.workingOnly().closed()
        !StateLabels.needsHumanOnly().closed()
        !StateLabels.deliveredOnly().closed()
    }

    // More than one label at once is a fact, not a contradiction: the claim sequence's own window
    // leaves a task wearing both ready and working until the ready label comes off.
    def "a task may wear more than one label at once"() {
        given:
        def labels = new StateLabels(true, true, false, false, false)

        expect:
        labels.ready()
        labels.working()
    }
}
