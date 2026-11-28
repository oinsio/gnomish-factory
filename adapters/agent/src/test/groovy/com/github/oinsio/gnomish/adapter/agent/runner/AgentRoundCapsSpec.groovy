package com.github.oinsio.gnomish.adapter.agent.runner

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The stdout caps of an agent round (FR1, FR8, NFR-R1 of extract-agent-round-runner): a line past the
 * line cap is parsed as its capped prefix, the events past the retained cap are dropped between the
 * round's opening and its latest lines, the truncation is one WARN, and the round completes on what
 * it kept. {@link DialectLines} is driven directly for what one line does; the round is driven whole
 * for what the caps cost it.
 */
@Timeout(60)
class AgentRoundCapsSpec extends Specification {

    @TempDir
    Path workspace

    @TempDir
    Path stderrParent

    LogCaptureSupport logs = LogCaptureSupport.attach(DialectLines, Level.DEBUG)

    def cleanup() {
        logs.detach()
    }

    // Scenario: executor/agent-round-runner / Oversized stdout does not grow the heap
    def "FR8, NFR-R1: a CLI writing past the caps is parsed on a capped buffer, the truncation logged, and the round completes"() {
        given: 'lines of at most 64 bytes, 256 bytes of them kept; one line over the cap and forty tool calls'
        def fixture = new RunnerFixture(workspace: workspace, stderrParent: stderrParent, caps: new StdoutCaps(64, 256))
        def tools = (1..40).collect {
            "use Tool${String.format('%02d', it)}\n"
        }.join()
        def stdout = 'hello fake-model-1 session-9\n' + 'use ' + 'x' * 200 + '\n' + tools + 'done all good\n'

        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(0, stdout))

        then: 'the round completes, judged on its opening and its end'
        result.status() == Status.COMPLETED
        def kept = fixture.dialect.judged.stamped()*.event()
        kept.first() == new FakeEvent('hello', 'fake-model-1 session-9')
        kept.last() == new FakeEvent('done', 'all good')
        kept.size() < 43

        and: 'the overlong line reached the dialect cut to the cap'
        fixture.dialect.parsed[1] == 'use ' + 'x' * 60

        and: 'one WARN with its code says what the caps cost'
        def warns = logs.list.findAll { it.level == Level.WARN }
        warns.size() == 1
        warns[0].formattedMessage.startsWith(OperatorEvent.AGENT_STDOUT_CAPPED.head())
        warns[0].formattedMessage.contains("agent executor 'fake' wrote past its stdout caps: 1 line(s) cut at 64 bytes, ")
        warns[0].formattedMessage.contains(" event(s) dropped past 256 bytes kept")
    }

    // FR8: a round within its caps says nothing about them
    def "FR8: a round within its caps logs no truncation"() {
        given:
        def fixture = new RunnerFixture(workspace: workspace, stderrParent: stderrParent)

        when:
        fixture.start(RunnerFixture.request(Role.TRANSFORM), RunnerFixture.exited(0, 'hello m s\ndone\n'))

        then:
        logs.list.findAll { it.level == Level.WARN }.isEmpty()
    }

    // FR8: #what alone is reported
    def "FR8: a round that only #what is reported as #words"() {
        given:
        def lines = new DialectLines<FakeEvent>(new FakeDialect(), { } as AgentProgressListener, new VirtualClock(), new StdoutCaps(8, 16))

        when:
        feed.each { lines.line(it[0].getBytes(StandardCharsets.UTF_8), it[1]) }
        lines.reportCaps('fake')

        then:
        logs.list.size() == 1
        logs.list[0].formattedMessage.contains(words)

        where:
        what | feed || words
        'cut a line' | [['use a', true]] || '1 line(s) cut at 8 bytes, 0 event(s) dropped'
        'dropped events' | (1..4).collect {
            ["use t$it", false]
        } || '0 line(s) cut at 8 bytes, 2 event(s) dropped past 16 bytes kept'
    }

    // FR2, FR6: a blank line is no line; any other goes to the dialect against the history, its progress handed on at once
    def "FR2, FR6: blank lines are skipped, and each other line is parsed against the history and its progress delivered"() {
        given:
        def dialect = new FakeDialect()
        List<AgentProgressEvent> delivered = []
        def clock = new VirtualClock(Instant.parse('2026-11-20T10:00:00Z'))
        def lines = new DialectLines<FakeEvent>(dialect, {
            delivered << it
        } as AgentProgressListener, clock, StdoutCaps.DEFAULTS)

        when:
        [
            '',
            '   ',
            'hello m s',
            'chatter',
            'use Read'
        ].each {
            lines.line(it.getBytes(StandardCharsets.UTF_8), false)
        }

        then:
        dialect.parsed == [
            'hello m s',
            'chatter',
            'use Read'
        ]
        lines.events().stamped()*.event()*.kind == ['hello', 'use']
        lines.events().stamped()*.at().every { it == clock.instant() }
        delivered*.getClass() == [
            AgentProgressEvent.RoundStarted,
            AgentProgressEvent.ToolStarted
        ]
    }
}
