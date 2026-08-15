package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import spock.lang.Specification

/**
 * DeclineFinishedMessage: the single core composer of the {@code declineFinished} comment text,
 * shared by {@code FinishedDecline} and {@code TakeDisposition} (design D3, D4 of
 * enforce-finish-terminality).
 *
 * Implements UX1 of enforce-finish-terminality.
 */
class DeclineFinishedMessageSpec extends Specification {

    // UX1: the comment states the task is already finished/completed, that nothing more will
    //     happen on it, and directs the human to open a new task or bug referencing this one.
    def "composes a message stating the task is finished, inert, and what to do instead"() {
        given:
        def ref = new TaskRef('github:o/r#42')

        when:
        def message = DeclineFinishedMessage.forTask(ref)

        then:
        message.contains(ref.id())
        message.toLowerCase().contains('finished')
        message.toLowerCase().contains('nothing more will happen')
        message.toLowerCase().contains('open a new task or bug')
    }

    // UX1: the reader must know which task to reference when opening the new one — the message
    //     names the declined task's id more than once so it reads self-contained on its own.
    def "references the declined task's own id"() {
        expect:
        DeclineFinishedMessage.forTask(new TaskRef('github:acme/widgets#7')).count('github:acme/widgets#7') >= 1
    }
}
