package com.github.oinsio.gnomish.app.conformance

import com.github.oinsio.gnomish.executorcontract.Answer
import com.github.oinsio.gnomish.executorcontract.WireViolation

import static com.github.oinsio.gnomish.app.conformance.KitSubjectDouble.*

import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.WireDocument
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.testfixtures.executor.RenderedPolicy
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR3, FR12, FR24 of define-executor-contract: how the kit judges one answer — a schema breach
 * first, then the status, then what the role asks of a completed answer, read through the
 * contract's typed reading — and how a verdict and a report are printed (UX2).
 */
class CaseJudgementSpec extends Specification {

    private static CaseVerdict judged(Role role, ExecutorResult result, List<WireBreach> breaches = []) {
        CaseJudgement.judge(CaseJudgement.request(role, new Workspace('/w/case'), ExecutorContractJson.settingsTree([:])), result, breaches)
    }

    def "FR12: in role #role an answer #shape is judged '#verdict'"() {
        expect:
        judged(role, result).line(KitCase.TRANSFORM) == verdict

        where:
        role | shape | result || verdict
        Role.TRANSFORM | 'completed' | result(Status.COMPLETED) || 'pass transform'
        Role.VERDICT | 'with a verdict' | result(Status.COMPLETED, VERDICT) || 'pass transform'
        Role.VERDICT | 'with no answer' | result(Status.COMPLETED) || 'FAIL transform: completed with no answer, although role verdict needs one'
        Role.VERDICT | 'choosing' | result(Status.COMPLETED, SELECT_FIRST) || 'FAIL transform: answered a choice where a verdict was asked'
        Role.VERDICT | 'asking' | result(Status.COMPLETED, QUESTION) || 'FAIL transform: asked a question where a verdict was asked'
        Role.DECISION | 'with a verdict' | result(Status.COMPLETED, VERDICT) || 'FAIL transform: answered a verdict where a decision was asked'
        Role.DECISION | 'choosing an option' | result(Status.COMPLETED, SELECT_FIRST) || 'pass transform'
        Role.DECISION | 'asking with two options' | result(Status.COMPLETED, QUESTION) || 'pass transform'
        Role.DECISION | 'choosing a prefix' | result(Status.COMPLETED, '{"kind":"select","chosen":"first"}') || "FAIL transform: chose 'first', which is none of the options the request offered"
        Role.DECISION | 'choosing a longer text' | result(Status.COMPLETED, '{"kind":"select","chosen":"first option!"}') || "FAIL transform: chose 'first option!', which is none of the options the request offered"
        Role.DECISION | 'of an unknown kind' | result(Status.COMPLETED, '{"kind":"vote"}') || 'FAIL transform: answered a malformed answer: ' + malformed('{"kind":"vote"}')
        Role.TRANSFORM | 'failed with a reason' | failed('no budget') || 'FAIL transform: answered failed/infrastructure: no budget'
        Role.TRANSFORM | 'failed with none' | failed(null) || 'FAIL transform: answered failed/infrastructure: no reason given'
        Role.TRANSFORM | 'pending' | result(Status.PENDING, null, 'k') || 'FAIL transform: answered pending to a request that asked for no pending run'
    }

    def "FR24: a schema breach fails the case first, whatever the result says"() {
        expect:
        judged(Role.TRANSFORM, result(Status.COMPLETED), [
            breach(WireDocument.RESULT),
            breach()
        ]) == new CaseVerdict.Failed(breach(WireDocument.RESULT).detail())
        breach(WireDocument.RESULT).detail() == 'the result document breaks its schema at enforcementLevel (enum): not in the enum'
        new WireBreach(WireDocument.ANSWER, new WireViolation('', 'json', UntrustedText.subprocess('cut'))).detail() ==
                'the answer document breaks its schema (json): cut'
    }

    // Scenario: executor/wire-contract / Policy is not a vendor tool name
    def "FR12: each role's request carries its own inputs and policy"() {
        expect:
        def request = CaseJudgement.request(role, new Workspace('/w/x'), ExecutorContractJson.settingsTree([:]))
        request.policy().getClass() == policy
        role.accepts(request.roleInputs())
        request.feedback() == [] && request.continuation() == null

        and: 'FR11: the request as written holds the abstract policy token alone and no vendor tool identifier'
        def written = ExecutorContractJson.writer().writeValueAsString(request)
        RenderedPolicy.abstractOnly(written)
        RenderedPolicy.vendorWords(written) == []

        where:
        role | policy
        Role.TRANSFORM | Policy.WorkspaceWrite
        Role.VERDICT | Policy.ReadOnly
        Role.DECISION | Policy.ReadOnly
        Role.EFFECT | Policy.ReadOnly
    }

    def "UX2: a line caps its detail at #CaseVerdict.DETAIL_CAP_CHARS characters, and a report counts its failures"() {
        given:
        String exact = 'x' * CaseVerdict.DETAIL_CAP_CHARS

        expect:
        new CaseVerdict.Failed(exact).line(KitCase.VERDICT) == 'FAIL verdict: ' + exact
        new CaseVerdict.NotApplicable(exact + 'y').line(KitCase.VERDICT) == 'n/a  verdict: ' + exact + '…'
        new KitReport([(KitCase.VERDICT): new CaseVerdict.Failed('a'), (KitCase.DESCRIBE): new CaseVerdict.Passed(),
            (KitCase.TRANSFORM): new CaseVerdict.Failed('b'), (KitCase.DECISION): new CaseVerdict.NotApplicable('c')]).failures() == 2
        new KitReport([(KitCase.VERDICT): new CaseVerdict.Passed(), (KitCase.DESCRIBE): new CaseVerdict.Passed()]).lines() ==
        [
            'pass describe',
            'pass verdict'
        ]
    }

    private static String malformed(String answer) {
        def reading = Answer.read(tree(answer), { String t ->
            UntrustedText.subprocess(t)
        })
        reading.reason().excerpt(300)
    }
}
