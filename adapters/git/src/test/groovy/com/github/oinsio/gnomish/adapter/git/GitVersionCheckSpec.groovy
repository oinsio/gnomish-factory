package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.git.GitVersionRefusedException
import com.github.oinsio.gnomish.gittransfer.GitVersion
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.testfixtures.standin.StandInLog
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR10, NFR-R2, NFR-O1, UX2, design D8 of own-git-transfer-argv: the startup floor check runs
 * {@code git --version} through the runner once per process, turns the captured line into the
 * leaf's typed {@link GitVersion}, logs one INFO line on pass, and refuses below the floor — or on
 * a git that cannot report a version — with one coded ERROR line and an exception naming floor,
 * installed and reason. A fake git binary answers with each recorded shape, so no real git's
 * version decides the outcome.
 */
class GitVersionCheckSpec extends Specification {

    @TempDir
    Path tempDir

    private LogCaptureSupport logs

    def setup() {
        logs = LogCaptureSupport.attach(GitVersionCheck, Level.INFO)
    }

    def cleanup() {
        logs.detach()
    }

    def "FR10, NFR-O1: a git at or above the floor passes with one INFO line naming the typed version: #line"() {
        given: 'a git that reports the line'
        def check = new GitVersionCheck(new GitProcessRunner(StandIn.git(preset).toString()))

        when:
        check.verify()

        then: 'one INFO line names the version — rendered from the leaf\'s typed value, not the captured line — and the floor'
        logs.list.size() == 1
        logs.list[0].level == Level.INFO
        logs.list[0].formattedMessage.contains(expected.toString())
        logs.list[0].formattedMessage.contains(GitVersion.FLOOR.toString())

        where:
        line | preset || expected
        'git version 2.45.1' | 'version-2-45-1' || new GitVersion(2, 45, 1)
        'git version 2.55.0 (Apple Git-200)' | 'version-2-55-apple' || new GitVersion(2, 55, 0)
    }

    def "FR10, UX2, NFR-O1: a git below the floor is refused, naming floor, installed and reason"() {
        given: 'a git one release below the floor'
        def check = new GitVersionCheck(new GitProcessRunner(StandIn.recording(tempDir, 'version-2-44').toString()))

        when:
        check.verify()

        then: 'the refusal reads like a precondition'
        def refused = thrown(GitVersionRefusedException)
        refused.message.contains('2.45.1')
        refused.message.contains('2.44.0')
        refused.message.contains(GitVersion.FLOOR_REASON)

        and: 'one coded ERROR line carries the same two values and the reason'
        logs.list.size() == 1
        logs.list[0].level == Level.ERROR
        logs.list[0].formattedMessage.startsWith(OperatorEvent.STARTUP_GIT_VERSION_REFUSED.head())
        logs.list[0].formattedMessage.contains('2.45.1')
        logs.list[0].formattedMessage.contains('2.44.0')
        logs.list[0].formattedMessage.contains(GitVersion.FLOOR_REASON)
    }

    def "NFR-R2: a git whose output is not a version line is refused the same way, quoting the output"() {
        given: 'a git that prints garbage'
        def check = new GitVersionCheck(new GitProcessRunner(StandIn.git('version-not-git').toString()))

        when:
        check.verify()

        then:
        def refused = thrown(GitVersionRefusedException)
        refused.message.contains('2.45.1')
        refused.message.contains('did not report a version')
        refused.message.contains('not a git at all')
        refused.message.contains(GitVersion.FLOOR_REASON)

        and:
        logs.list.size() == 1
        logs.list[0].level == Level.ERROR
        logs.list[0].formattedMessage.startsWith(OperatorEvent.STARTUP_GIT_VERSION_REFUSED.head())
        logs.list[0].formattedMessage.contains('not a git at all')
    }

    def "NFR-R2: a git that exits non-zero is refused the same way, quoting its stderr"() {
        given: 'a git that fails to answer'
        def check = new GitVersionCheck(new GitProcessRunner(StandIn.git('version-broken').toString()))

        when:
        check.verify()

        then:
        def refused = thrown(GitVersionRefusedException)
        refused.message.contains('did not report a version')
        refused.message.contains(brokenInstall())

        and:
        logs.list.size() == 1
        logs.list[0].level == Level.ERROR
        logs.list[0].formattedMessage.contains(brokenInstall())
    }

    def "NFR-R2: the check runs git once per process — a second call answers from the first"() {
        given: 'a git that records every invocation'
        def git = StandIn.recording(tempDir, 'version-record-2-55')
        def check = new GitVersionCheck(new GitProcessRunner(git.toString()))

        when:
        check.verify()
        check.verify()

        then: 'one subprocess, one INFO line'
        StandInLog.blocks(git).collect {
            StandInLog.argv(it)
        } == [['--version']]
        logs.list.size() == 1
    }

    /** The stderr of the broken-install stand-in, read from its preset rather than retyped. */
    private static String brokenInstall() {
        StandIn.data('stderr#git-broken-install').trim()
    }
}
