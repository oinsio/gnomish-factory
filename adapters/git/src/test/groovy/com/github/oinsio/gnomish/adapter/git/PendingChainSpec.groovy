package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR20 of migrate-verify-to-executor-contract (design D21): the walk's own refusals on reads the real
 * medium cannot produce on demand — a commit git cannot read in this clone, and an answer that is
 * not one parents line and one subject line — scripted on the runner port. Each ends the walk with
 * nothing and leaves a DEBUG trace naming the commit; the walk over real commits is
 * {@code SnapshotTipCheckSpec}'s.
 */
class PendingChainSpec extends Specification {

    static final String PENDING = 'a' * 40
    static final String PARENT = 'b' * 40
    static final RoundToken TOKEN = RoundToken.of('c' * 40)
    static final AttemptKey KEY = new AttemptKey('TIP-1', 'implement', 3)
    static final List<String> LOG = [
        'log',
        '-1',
        '--format=%P%n%s'
    ]

    def git = new ScriptedGit()

    private Optional<PendingChain.Walk> walk() {
        new PendingChain(git, Path.of('clone')).snapshotBelow('gnomish/TIP-1', new RegisteredCommit(PENDING), KEY, TOKEN)
    }

    def "FR20: a parent git cannot read is no snapshot, and the failed read is traced"() {
        given:
        git.answer(LOG + PENDING, 0, PARENT + '\n' + ServiceCommitMessages.pending('implement', 3, TOKEN) + '\n')
        git.answer(LOG + PARENT, 128, '', 'fatal: bad object ' + PARENT + '\n')
        def logs = LogCaptureSupport.attach(PendingChain, Level.DEBUG)

        when:
        def found = walk()
        def lines = logs.list*.formattedMessage
        logs.detach()

        then:
        found.isEmpty()
        lines.any {
            it.contains('could not read ' + PARENT) && it.contains('git exited 128') && it.contains('bad object')
        }
        lines.any {
            it.contains('pending commit ' + PENDING + ' of gnomish/TIP-1 stands on no snapshot')
        }
    }

    def "FR20: an answer that is not one parents line and one subject line is refused (#shape)"() {
        given:
        git.answer(LOG + PENDING, 0, PARENT + '\n' + ServiceCommitMessages.pending('implement', 3, TOKEN) + '\n')
        git.answer(LOG + PARENT, 0, answer)
        def logs = LogCaptureSupport.attach(PendingChain, Level.DEBUG)

        when:
        def found = walk()
        def lines = logs.list*.formattedMessage
        logs.detach()

        then:
        found.isEmpty()
        lines.any { it.contains('read no subject at ' + PARENT) }

        where:
        shape | answer
        'no subject' | PARENT + '\n'
        'a third line' | PARENT + '\nsubject\nmore\n'
    }

    def "FR20: the snapshot found below one pending commit is that commit's only parent"() {
        given:
        git.answer(LOG + PENDING, 0, PARENT + '\n' + ServiceCommitMessages.pending('implement', 3, TOKEN) + '\n')
        git.answer(LOG + PARENT, 0, 'd' * 40 + '\n' + ServiceCommitMessages.snapshot('implement', 3, TOKEN) + '\n')

        expect:
        walk() == Optional.of(new PendingChain.Walk([PENDING, PARENT], RoundClose.VERIFIABLE))
        git.calls()*.command() == [LOG + PENDING, LOG + PARENT]
    }

    // FR3, D14 as amended 2026-11-13: one walk, two answers — the chain is exactly the commits the walk read
    def "FR3: the chain read is the commits the walk read, in order, the snapshot last"() {
        given: 'a rotated pending commit above an earlier one, above the snapshot'
        def middle = 'e' * 40
        git.answer(LOG + PENDING, 0, middle + '\n' + ServiceCommitMessages.pending('implement', 3, TOKEN) + '\n')
        git.answer(LOG + middle, 0, PARENT + '\n' + ServiceCommitMessages.pending('implement', 3, TOKEN) + '\n')
        git.answer(LOG + PARENT, 0, 'd' * 40 + '\n' + ServiceCommitMessages.snapshot('implement', 3, TOKEN) + '\n')

        when:
        def chain = walk().get().chain()

        then:
        chain == [PENDING, middle, PARENT]
        git.calls()*.command() == chain.collect { LOG + it }
    }

    // FR11 of extract-agent-round-runner (design D11, "Resume"): the bottom of the chain names its kind
    def "FR11: a walk ending on the round's #bottom answers #close"() {
        given:
        git.answer(LOG + PENDING, 0, PARENT + '\n' + ServiceCommitMessages.pending('implement', 3, TOKEN) + '\n')
        git.answer(LOG + PARENT, 0, 'd' * 40 + '\n' + subject + '\n')

        expect:
        walk() == Optional.of(new PendingChain.Walk([PENDING, PARENT], close))

        where:
        bottom | subject || close
        'snapshot' | ServiceCommitMessages.snapshot('implement', 3, TOKEN) || RoundClose.VERIFIABLE
        'pending-round snapshot' | ServiceCommitMessages.pendingSnapshot('implement', 3, TOKEN) || RoundClose.PENDING
    }
}
