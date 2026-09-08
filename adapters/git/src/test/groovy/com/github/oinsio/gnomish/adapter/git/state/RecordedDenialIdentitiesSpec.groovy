package com.github.oinsio.gnomish.adapter.git.state

import com.github.oinsio.gnomish.domain.engine.DenialIdentity
import spock.lang.Specification

/**
 * FR7 of fix-denial-attribution-durability: the identities a branch tip records, gathered across
 * both envelopes. They are what a resume hands its environment beside the committed position, so a
 * read that cannot use that position merges its full re-read instead of doubling the report.
 */
class RecordedDenialIdentitiesSpec extends Specification {

    private static StateDenialDto denial(String at) {
        new StateDenialDto('egress denied: paste.example.com:443', null, null,
                at == null ? null : new DenialIdentityDto('src-1', at))
    }

    private static StateJsonDto stateWith(List<StateDenialDto> denials) {
        new StateJsonDto(1, new StatePositionDto.AtStage('atStage', 'build'), 1, [
            new StateAttemptDto(0, 'passed', '2026-08-19T10:00:00Z', [], denials, null, null)
        ], null, null)
    }

    private static TaskJsonDto taskWith(EscalationReportDto escalation) {
        new TaskJsonDto(1, 'T-1', 't', 'b', '2026-08-19T09:00:00Z', 'abc123', [], null, escalation, null, null, null, null)
    }

    def "FR7: identities are gathered from both envelopes"() {
        given:
        def state = stateWith([
            denial('2026-08-19T10:00:00Z')
        ])
        def task = taskWith(new EscalationReportDto.CannotExecute(
                        'cannotExecute', 'round timed out', [
                            denial('2026-08-19T10:05:00Z')
                        ]))

        expect:
        RecordedDenialIdentities.of(state, task) == [
            new DenialIdentity('src-1', '2026-08-19T10:00:00Z'),
            new DenialIdentity('src-1', '2026-08-19T10:05:00Z')
        ] as Set
    }

    // FR7: "unknown, keep" — an entry with no identity contributes nothing, so it is re-read and
    //     re-attached rather than silently merged away (design D3)
    def "FR7: a denial entry with no identity contributes nothing"() {
        expect:
        RecordedDenialIdentities.of(stateWith([denial(null)]), null).isEmpty()
    }

    def "FR7: an escalation of another kind carries no identities"() {
        expect:
        RecordedDenialIdentities.of(null, taskWith(new EscalationReportDto.AttemptsExhausted('attemptsExhausted', 3)))
                .isEmpty()
    }

    def "FR7: a tip with neither envelope records nothing"() {
        expect:
        RecordedDenialIdentities.of(null, null).isEmpty()
    }
}
