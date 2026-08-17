package com.github.oinsio.gnomish.app

import spock.lang.Specification

/**
 * NFR-S2, design D5 of add-plugin-architecture: the engine's side of the fixed interpolation
 * whitelist. A provider asks for a name and either gets the run's value or nothing — it can neither
 * enumerate more names into existence nor be handed a value the engine did not decide to expose.
 */
class CheckRunContextSpec extends Specification {

    def "the whitelisted names are stable"() {
        expect:
        CheckRunContext.TASK_ID == 'task.id'
        CheckRunContext.TASK_BRANCH == 'task.branch'
        CheckRunContext.STAGE_NAME == 'stage.name'
    }

    // NFR-S2: the wiring default supplies nothing, so a check that interpolates fails closed rather
    //     than quietly addressing a placeholder.
    def "the empty context answers every lookup with nothing"() {
        given:
        def context = CheckRunContext.none()

        expect:
        context.value(CheckRunContext.TASK_ID).isEmpty()
        context.value(CheckRunContext.TASK_BRANCH).isEmpty()
        context.value(CheckRunContext.STAGE_NAME).isEmpty()
        context.value('anything.else').isEmpty()
    }
}
