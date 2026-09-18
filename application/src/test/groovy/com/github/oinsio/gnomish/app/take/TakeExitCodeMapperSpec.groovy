package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR9, FR10, FR15, D16 of add-tracker-port: every {@link TakeResult} variant (and every
 * {@link ParkReason} for {@link TakeResult.AwaitingHuman}) maps to its documented exit code,
 * per the tracker-take spec's "Exit codes by take result" table.
 */
class TakeExitCodeMapperSpec extends Specification {

    private static final TaskState STATE = TaskState.atStageStart('implement')

    def "exitCodeFor maps #result.class.simpleName to #expectedCode"() {
        expect:
        TakeExitCodeMapper.exitCodeFor(result) == expectedCode

        where:
        result | expectedCode
        new TakeResult.Delivered(STATE, UntrustedText.tracker('done')) | 0
        new TakeResult.EmptyQueue() | 0
        new TakeResult.AwaitingHuman(STATE, ParkReason.ESCALATION, 'decide') | 10
        new TakeResult.AwaitingHuman(STATE, ParkReason.CHECKPOINT, 'paused') | 11
        new TakeResult.AwaitingHuman(STATE, ParkReason.INFRA, 'fix and retry') | 13
        new TakeResult.Aborted(STATE, UntrustedText.subprocess('persist failed')) | 12
        new TakeResult.Revoked(STATE, UntrustedText.tracker('work stopped')) | 14
        new TakeResult.Skipped(UntrustedText.tracker('held by another instance')) | 15
        new TakeResult.InfrastructureUnavailable(UntrustedText.subprocess('origin never answered')) | 16
    }
}
