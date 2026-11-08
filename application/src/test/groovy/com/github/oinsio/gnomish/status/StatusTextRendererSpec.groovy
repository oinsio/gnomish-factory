package com.github.oinsio.gnomish.status

import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorRole
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.MismatchCause
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.UsageCost
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * StatusTextRenderer: renders a StatusReport as human-readable English text
 * (task 6.4 of add-manual-run) — a full multi-line report and a one-line
 * per-attempt summary. Implements FR10, UX2, D7 of add-manual-run.
 */
class StatusTextRendererSpec extends Specification {

    private static final Instant STARTED = Instant.parse('2026-07-16T14:35:10Z')

    private static TaskContext context(List<Decision> decisions = []) {
        new TaskContext('manual-20260716-143502-x7', UntrustedText.tracker('Fix flaky spec'), UntrustedText.tracker('body text'), decisions)
    }

    private static AttemptRecord passedRound(int round = 0) {
        def check = new CheckResult(new CheckRef(0, UntrustedText.manifest('builtin:files_exist')), new Verdict.Pass(null, []), Duration.ofMillis(3))
        AttemptRecords.of(round, AttemptRecord.Result.PASSED, STARTED, [check], ExecutorUsage.none(), [], Stop.none())
    }

    private static AttemptRecord failedRound(int round = 0) {
        def check = new CheckResult(new CheckRef(0, UntrustedText.manifest('command:./gradlew test')),
                new Verdict.Fail([]), Duration.ofSeconds(5))
        AttemptRecords.of(round, AttemptRecord.Result.QUALITY_FAILURE, STARTED, [check],
        new ExecutorUsage(Duration.ofSeconds(5), [], [:]), [], Stop.none())
    }

    // FR10, UX2: renderAttemptSummary is genuinely one line and mentions the round and result
    def "renderAttemptSummary is one line mentioning the round and result"() {
        given:
        def renderer = new StatusTextRenderer()

        when:
        def line = renderer.renderAttemptSummary(failedRound(2))

        then:
        !line.contains('\n')
        line.contains('Round 2')
        line.contains('quality failure')
    }

    // FR10, UX2: renderAttemptSummary highlights check pass/fail counts and duration
    def "renderAttemptSummary highlights a passing round"() {
        given:
        def renderer = new StatusTextRenderer()

        when:
        def line = renderer.renderAttemptSummary(passedRound(0))

        then:
        line.contains('Round 0')
        line.contains('passed')
        line.contains('1 checks passed')
    }

    // FR10, UX2, D7: renderFull includes recognizable content for a fully populated report
    def "renderFull includes stage, attempts, decisions, totals, escalation and last decision"() {
        given:
        def renderer = new StatusTextRenderer()
        def state = TaskState.atStageStart('implement').recordQualityFailure(failedRound(0), ExecutorVersions.none())
        def decision = new Decision('patch in place', 'plan', 'operator', STARTED)
        def ctx = context([decision])
        def escalation = new EscalationReport.DecisionNeeded(UntrustedText.agent('Refactor or patch?'), [
            UntrustedText.agent('refactor'),
            UntrustedText.agent('patch')
        ])
        def report = StatusReport.build(ctx, state, escalation, null)

        when:
        def text = renderer.renderFull(report)

        then:
        text.contains(ctx.taskId())
        text.contains(ctx.title().forConsole())
        text.contains('Stage: implement\n')
        text.contains('Round 0')
        text.contains('Decisions:')
        text.contains('patch in place')
        text.contains('Totals: tokensByModel=unknown\n')
        text.contains('decision needed: Refactor or patch?')
        text.contains('Last decision:')
        text.contains('patch in place')
    }

    // FR11: renderFull renders "pipeline complete" at pipelineEnd
    def "renderFull renders pipeline complete at pipelineEnd"() {
        given:
        def renderer = new StatusTextRenderer()
        def state = TaskState.atStageStart('implement').advanceTo(new Position.PipelineEnd())
        def report = StatusReport.build(context(), state, null, null)

        when:
        def text = renderer.renderFull(report)

        then:
        text.contains('pipeline complete')
    }

    // UX1, FR12 of make-checkpoint-gate-durable: a gate renders as awaiting approval after its stage
    // Scenario: observability/status-report / A gate renders as awaiting approval
    def "renderFull renders a gate as awaiting approval after the stage that passed"() {
        given:
        def renderer = new StatusTextRenderer()
        def state = new TaskState(new Position.AwaitingApproval('release'), 0, [], UsageSnapshot.EMPTY, ExecutorVersions.none())
        def report = StatusReport.build(context(), state, null, null)

        when:
        def text = renderer.renderFull(report)

        then:
        text.contains("Stage: awaiting approval after 'release'\n")
    }

    // FR10, UX2: renderFull omits optional sections that are absent
    def "renderFull omits attempts, decisions, escalation and last-decision sections when absent"() {
        given:
        def renderer = new StatusTextRenderer()
        def state = TaskState.atStageStart('implement')
        def report = StatusReport.build(context(), state, null, null)

        when:
        def text = renderer.renderFull(report)

        then:
        !text.contains('Attempts:')
        !text.contains('Decisions:')
        !text.contains('Last escalation:')
        !text.contains('Last decision:')
    }

    // NFR-C1: renderFull renders "unknown" for null totals rather than fabricating zeros
    def "renderFull renders unknown totals when usage is unreported"() {
        given:
        def renderer = new StatusTextRenderer()
        def report = StatusReport.build(context(), TaskState.atStageStart('implement'), null, null)

        when:
        def text = renderer.renderFull(report)

        then:
        text.contains('Totals: tokensByModel=unknown\n')
    }

    // FR17 of define-executor-contract: the totals line takes the snapshot's per-model tokens from the
    //     one usage fold, summed over the roles, in model-id order
    def "renderFull renders the snapshot's tokens per model, summed over the roles"() {
        given:
        def renderer = new StatusTextRenderer()
        def tokens = new TokenUsage(1, 2, 3, 4)
        def snapshot = new UsageSnapshot([(ExecutorRole.TRANSFORM): ['z': tokens, 'a': tokens], (ExecutorRole.VERDICT): ['a': tokens]],
        new UsageCost(BigDecimal.ZERO, [:], false), 2, null)
        def state = new TaskState(new Position.AtStage('implement'), 0, [], snapshot, ExecutorVersions.none())

        when:
        def text = renderer.renderFull(StatusReport.build(context(), state, null, null))

        then:
        text.contains('Totals: tokensByModel={a=TokenUsage[input=2, output=4, cacheCreation=6, cacheRead=8], z=TokenUsage[input=1, output=2, cacheCreation=3, cacheRead=4]}\n')
    }

    // Scenario: observability/status-report / Text render names the snapshot's position
    // UX4 of define-executor-contract: beside the totals, the rounds folded and the cost as a stated
    //     lower bound naming the unpriced tokens, in the words gnomish usage --markdown uses
    def "UX4: an incomplete snapshot renders its rounds and 'cost incomplete: N tokens unpriced' beside the totals"() {
        given:
        def renderer = new StatusTextRenderer()
        def tokens = new TokenUsage(1, 2, 3, 4)
        def snapshot = new UsageSnapshot([(ExecutorRole.TRANSFORM): ['m': tokens], (ExecutorRole.VERDICT): ['j': tokens]],
        new UsageCost(new BigDecimal('0.25'), [(ExecutorRole.VERDICT): ['j': tokens]], false), 3, null)
        def state = new TaskState(new Position.AtStage('implement'), 0, [], snapshot, ExecutorVersions.none())

        when:
        def text = renderer.renderFull(StatusReport.build(context(), state, null, null))

        then:
        text.contains('Totals: tokensByModel={j=TokenUsage[input=1, output=2, cacheCreation=3, cacheRead=4], '
                + 'm=TokenUsage[input=1, output=2, cacheCreation=3, cacheRead=4]}\n'
                + '  rounds folded: 3; cost at least $0.2500; cost incomplete: 10 tokens unpriced\n')
    }

    // UX4 of define-executor-contract: a complete snapshot names its rounds and its priced sum, no qualifier
    def "UX4: a complete snapshot renders its rounds and the priced sum beside the totals"() {
        given:
        def renderer = new StatusTextRenderer()
        def snapshot = new UsageSnapshot([(ExecutorRole.TRANSFORM): ['m': new TokenUsage(1, 0, 0, 0)]],
        new UsageCost(new BigDecimal('1.5'), [:], true), 1, null)
        def state = new TaskState(new Position.AtStage('implement'), 0, [], snapshot, ExecutorVersions.none())

        when:
        def text = renderer.renderFull(StatusReport.build(context(), state, null, null))

        then:
        text.contains('  rounds folded: 1; cost $1.50\n')
        !text.contains('incomplete')
    }

    // FR11, D7: renderFull renders every EscalationReport kind without throwing
    def "renderFull renders every escalation report kind"() {
        given:
        def renderer = new StatusTextRenderer()
        def state = TaskState.atStageStart('implement')

        expect:
        renderer.renderFull(StatusReport.build(context(), state, escalation, null)).contains(expectedFragment)

        where:
        escalation | expectedFragment
        new EscalationReport.AttemptsExhausted(3) | 'attempts exhausted'
        new EscalationReport.DecisionNeeded(UntrustedText.agent('Refactor?'), [
            UntrustedText.agent('a'),
            UntrustedText.agent('b')
        ]) | 'decision needed'
        new EscalationReport.CannotVerify(new CheckRef(0, UntrustedText.manifest('command:x')), UntrustedText.subprocess('network error'), UntrustedText.subprocess('')) | 'cannot verify'
        new EscalationReport.PipelineMismatch(new MismatchCause.StaleStage(UntrustedText.branchDocument('stale-stage'))) | 'pipeline mismatch'
        new EscalationReport.CannotExecute(UntrustedText.subprocess('agent crashed'), []) | 'cannot execute'
        // FR7 of define-executor-contract: the sixth kind names the bound it reached
        new EscalationReport.BudgetExhausted(UntrustedText.agent('token bound 1000')) | 'budget exhausted: token bound 1000'
        // FR13 of define-executor-contract: an executor version mismatch names the executor and both versions
        new EscalationReport.PipelineMismatch(new MismatchCause.ExecutorVersion(UntrustedText.manifest('acme'),
                UntrustedText.branchDocument('1'), UntrustedText.subprocess('2'))) | 'pipeline mismatch: executor "acme" version 1 recorded, 2 described'
    }

    // FR6 of make-run-headless: the report carries no attempt limit, so the stage line names the
    //     stage alone, with no "(attempt n/m)" fraction
    def "renderFull renders the stage line without an attempt fraction"() {
        given:
        def state = TaskState.atStageStart('implement').recordQualityFailure(failedRound(0), ExecutorVersions.none())

        when:
        def text = new StatusTextRenderer().renderFull(StatusReport.build(context(), state, null, null))

        then:
        text.contains('Stage: implement\n')
        !text.contains('(attempt')
    }

    // UX1 of fix-denial-report-attachment: the reviewer reads the denial in the same block as the
    //     attempt, needing to know nothing about guard logs or container internals
    // Scenario: observability/status-report / The text render shows attempt denials
    def "renderFull lists a passing attempt's egress denials under its summary line"() {
        given: 'a passing round that recorded one denial'
        def denial = new Finding(
                'egress denied: paste.example.com:443', 'paste.example.com:443/upload', 'kind=http method=POST')
        def check = new CheckResult(new CheckRef(0, UntrustedText.manifest('builtin:files_exist')), new Verdict.Pass(null, []), Duration.ofMillis(3))
        def round = AttemptRecords.of(0, AttemptRecord.Result.PASSED, STARTED, [check],
        ExecutorUsage.none(), [Denial.unidentified(denial)], Stop.none())
        def state = new TaskState(new Position.AtStage('implement'), 1, [round], UsageSnapshot.EMPTY, ExecutorVersions.none())

        when:
        def text = new StatusTextRenderer().renderFull(StatusReport.build(context(), state, null, null))

        then: 'the denied destination and path are readable right under the round that caused them'
        text.contains('Round 0: passed')
        text.contains('egress denial: egress denied: paste.example.com:443 (paste.example.com:443/upload)')
    }

    // FR15 of add-sandbox-core, UX1 of fix-denial-report-attachment: a denial's host/path are gnome-chosen text, so the
    //     console sink strips ANSI/control sequences and keeps one denial on one line — a crafted
    //     path can neither rewrite the operator's terminal nor forge extra report lines. FR6 of
    //     harden-logging-observability moved the site onto the shared LogText choke point, which
    //     renders the surviving breaks as visible escapes rather than collapsing them to spaces:
    //     the forgery is still inert, and the operator can now see what the text tried to do.
    def "renderFull neutralizes escape sequences and line breaks in a denial's text"() {
        given: 'a denial whose path carries a terminal-clearing escape and a forged report line'
        def esc = '\u001B'
        def denial = new Finding(
                "egress denied: evil.example.com:443${esc}[31m",
                "evil.example.com:443/x${esc}[2K\nRound 9:\tpassed\rHIDDEN",
                'kind=http method=POST')
        def check = new CheckResult(new CheckRef(0, UntrustedText.manifest('builtin:files_exist')), new Verdict.Pass(null, []), Duration.ofMillis(3))
        def round = AttemptRecords.of(0, AttemptRecord.Result.PASSED, STARTED, [check],
        ExecutorUsage.none(), [Denial.unidentified(denial)], Stop.none())
        def state = new TaskState(new Position.AtStage('implement'), 1, [round], UsageSnapshot.EMPTY, ExecutorVersions.none())

        when:
        def text = new StatusTextRenderer().renderFull(StatusReport.build(context(), state, null, null))

        then: 'no control character survives, and the denial occupies exactly one line'
        !text.contains(esc)
        !text.contains('\r')
        !text.contains('\t')
        text.readLines().count { it.contains('egress denial:') } == 1
        text.contains('egress denial: egress denied: evil.example.com:443 '
                + '(evil.example.com:443/x\\nRound 9:\\tpassedHIDDEN)')
    }

    // FR2, UX1 of fix-denial-attribution-durability: the round that could not execute left
    //     no attempt line, so its denials hang under the escalation — the reviewer sees the
    //     blocked exfiltration beside the reason the round died, without reading any log.
    // Scenario: observability/status-report / The text render lists escalation denials beside the reason
    def "renderFull lists a cannotExecute escalation's denials under the escalation line"() {
        given: 'an escalation for a round killed on its timeout after a denied egress'
        def denial = new Finding(
                'egress denied: paste.example.com:443', 'paste.example.com:443/upload', 'kind=http method=POST')
        def escalation = new EscalationReport.CannotExecute(UntrustedText.subprocess('round timed out after 15m'), [Denial.unidentified(denial)])
        def state = TaskState.atStageStart('implement')

        when:
        def text = new StatusTextRenderer().renderFull(
                StatusReport.build(context(), state, escalation, null))

        then: 'the reason and the denial read together, and no attempt was invented to hold it'
        text.contains('Last escalation: cannot execute: round timed out after 15m')
        text.contains('egress denial: egress denied: paste.example.com:443 (paste.example.com:443/upload)')
        !text.contains('Attempts:')
    }

    // NFR-S1 of fix-denial-attribution-durability: the escalation's denials are gnome-chosen
    //     text like an attempt's, so they pass the same findings funnel — the escalation path
    //     must not be the one that skips it.
    // Scenario: observability/status-report / The text render lists escalation denials beside the reason
    def "renderFull neutralizes escape sequences in an escalation denial's text"() {
        given:
        def esc = '\u001B'
        def denial = new Finding(
                "egress denied: evil.example.com:443${esc}[31m",
                "evil.example.com:443/x${esc}[2K\nLast escalation:\tforged",
                'kind=http method=POST')
        def escalation = new EscalationReport.CannotExecute(UntrustedText.subprocess('round timed out'), [Denial.unidentified(denial)])

        when:
        def text = new StatusTextRenderer().renderFull(StatusReport.build(context(), TaskState.atStageStart('implement'), escalation, null))

        then: 'no control character survives and the denial stays one line'
        !text.contains(esc)
        !text.contains('\t')
        text.readLines().count { it.contains('egress denial:') } == 1

        and: 'the forged escalation head stays inside the denial line instead of becoming one'
        text.readLines().count { it.startsWith('Last escalation:') } == 1
        text.contains('(evil.example.com:443/x\\nLast escalation:\\tforged)')
    }

    // UX2: an escalation whose round recorded no denial renders exactly as it did before
    //     this change — no heading, no empty list.
    // Scenario: observability/status-report / Escalations without denials stay quiet
    def "renderFull renders nothing extra for a cannotExecute escalation with no denials"() {
        given:
        def escalation = new EscalationReport.CannotExecute(UntrustedText.subprocess('adapter crashed'), [])

        when:
        def text = new StatusTextRenderer().renderFull(StatusReport.build(context(), TaskState.atStageStart('implement'), escalation, null))

        then:
        text.contains('Last escalation: cannot execute: adapter crashed')
        !text.contains('denial')
    }

    // UX2: zero denials render nothing at all — no heading, no empty list
    // Scenario: sandbox/sandbox-egress / A quiet task shows nothing
    def "renderFull renders nothing for a round with no denials"() {
        given:
        def state = new TaskState(new Position.AtStage('implement'), 1, [passedRound(0)], UsageSnapshot.EMPTY, ExecutorVersions.none())

        when:
        def text = new StatusTextRenderer().renderFull(StatusReport.build(context(), state, null, null))

        then:
        text.contains('Round 0: passed')
        !text.contains('denial')
    }

    // FR8, UX3 of fix-denial-attribution-durability: the factory's own loss travels the findings
    //     channel, so the render must show it exactly like a denial — that is what lets a report
    //     say "no data" instead of implying "no denials". The wording is DenialLossMarker's; this
    //     spec pins the channel, not the sentence.
    // Scenario: sandbox/sandbox-egress / A saturated tail cap surfaces as loss
    def "UX3: a loss marker renders beside the denials it stands in for"() {
        given: 'a round reporting one real denial and one synthetic loss marker, neither identified'
        def denial = Denial.unidentified(
                new Finding('egress denied: paste.example.com:443', 'paste.example.com:443', 'kind=connect'))
        def marker = Denial.unidentified(new Finding(
                        'egress denial log truncated: older denials inside the read window are lost',
                        'gnomish-PROJ-9',
                        'loss window: after the guard container\'s start'))
        def check = new CheckResult(new CheckRef(0, UntrustedText.manifest('builtin:files_exist')), new Verdict.Pass(null, []), Duration.ofMillis(3))
        def round = AttemptRecords.of(0, AttemptRecord.Result.PASSED, STARTED, [check],
        ExecutorUsage.none(), [denial, marker], Stop.none())
        def state = new TaskState(new Position.AtStage('implement'), 1, [round], UsageSnapshot.EMPTY, ExecutorVersions.none())

        when:
        def text = new StatusTextRenderer().renderFull(StatusReport.build(context(), state, null, null))

        then: 'both lines are there, in read order, through the one funnel-fenced finding line'
        text.readLines().count { it.contains('egress denial:') } == 2
        text.contains('egress denial: egress denied: paste.example.com:443')
        text.contains('egress denial: egress denial log truncated: older denials inside the read window are lost')

        and: 'the marker gates nothing — the round is still passed (proposal NG1)'
        text.contains('Round 0: passed')
    }
}
