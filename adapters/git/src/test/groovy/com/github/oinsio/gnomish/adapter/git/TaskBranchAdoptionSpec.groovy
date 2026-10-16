package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.git.BranchAdoption
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.BranchLocationRefusedException
import com.github.oinsio.gnomish.app.port.git.BranchLocationUnavailableException
import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR7 of fix-terminal-receipt-convergence (design D9): {@link TaskBranchAdoption} makes a
 * remote-tracking-only task branch local by one ref transaction — {@code create} on absence at the
 * tracking tip, the register untouched — and answers a {@code create} lost to another adopter as
 * already local. Nothing undoes an adoption (design D12); the cross-clone consequence is pinned
 * by {@code DeliveredCleanupCrossCloneSpec} in {@code :bootstrap}. Specified against {@link ScriptedGit}: the transaction's script is the
 * subject, so the spec reads it line by line.
 */
class TaskBranchAdoptionSpec extends Specification {

    static final String TASK = 'PROJ-7'
    static final String HEAD = TaskBranchRefs.head(TASK)
    static final String TRACKING = TaskBranchRefs.tracking(TASK)
    static final String TIP = 'a' * 40
    static final String ABSENT = '0' * 40
    static final Path CLONE = Path.of('/clone')

    def git = ScriptedGit.reftableClone()

    private TaskBranchAdoption adoption() {
        new TaskBranchAdoption(git, new TaskBranchLocator(git, VirtualTimeGitRetries.gitInfrastructure()))
    }

    private List<String> transactions() {
        git.calls(['update-ref', '--stdin'])*.argv.collect { it.join('\n') }
    }

    // Scenario: git/git-task-persistence / A remote-tracking-only branch is adopted at its tracking tip
    def "FR7: a remote-tracking-only branch is created at the tracking tip by one create on absence, register untouched"() {
        given:
        git.answer([
            'rev-parse',
            '--verify',
            '--quiet',
            TRACKING
        ], 0, TIP + '\n')
        git.answer(['update-ref', '--stdin'], 0)
        def logs = LogCaptureSupport.attach(TaskBranchAdoption)

        when:
        def answer = adoption().adopt(CLONE, TASK, new BranchLocation.RemoteTracking(TRACKING))

        then: 'one transaction, the head alone, created only if absent — no git branch, no checkout'
        answer == new BranchAdoption.Adopted(TASK)
        transactions() == [
            [
                'update-ref',
                '--stdin',
                'start',
                "update $HEAD $TIP $ABSENT",
                'prepare',
                'commit'
            ].join('\n')
        ]
        git.calls(['branch']).isEmpty()
        git.calls(['checkout']).isEmpty()

        and: 'FR5 of harden-logging-observability: the adoption logs its own anchor, once'
        def anchors = logs.list.findAll { it.level == Level.INFO }
        anchors.size() == 1
        anchors[0].formattedMessage.contains('adopted branch ' + HEAD)
        anchors[0].formattedMessage.contains(TASK)
        anchors[0].formattedMessage.contains(TRACKING)
        anchors[0].formattedMessage.contains(TIP)

        cleanup:
        logs.detach()
    }

    def "FR7: a create lost to another adopter is read back through the locator and answers already local"() {
        given: 'the create is refused, and the re-read finds the winner\'s head'
        git.answer([
            'rev-parse',
            '--verify',
            '--quiet',
            TRACKING
        ], 0, TIP + '\n')
        git.answer(['update-ref', '--stdin'], 1, '', 'reference already exists')
        git.answer([
            'rev-parse',
            '--verify',
            '--quiet',
            HEAD
        ], 0, TIP + '\n')
        def logs = LogCaptureSupport.attach(TaskBranchAdoption, Level.DEBUG)

        when:
        def answer = adoption().adopt(CLONE, TASK, new BranchLocation.RemoteTracking(TRACKING))

        then:
        answer == new BranchAdoption.AlreadyLocal(TASK)
        git.calls([
            'rev-parse',
            '--verify',
            '--quiet',
            HEAD
        ]).size() == 1
        logs.list.count {
            it.level == Level.DEBUG && it.formattedMessage.contains(HEAD)
        } == 1
        logs.list.every { it.level != Level.INFO }

        cleanup:
        logs.detach()
    }

    def "FR7: a refused create that leaves no local branch is not a lost race and fails"() {
        given: 'the create is refused, and the re-read still finds only the tracking ref'
        git.answer([
            'rev-parse',
            '--verify',
            '--quiet',
            HEAD
        ], 1)
        git.answer([
            'rev-parse',
            '--verify',
            '--quiet',
            TRACKING
        ], 0, TIP + '\n')
        git.answer(['update-ref', '--stdin'], 128, '', 'fatal: cannot lock ref')
        git.answer(['fetch'], 0)

        when:
        adoption().adopt(CLONE, TASK, new BranchLocation.RemoteTracking(TRACKING))

        then:
        def failure = thrown(IllegalStateException)
        failure.message.contains(HEAD)
        failure.message.contains(TRACKING)
        failure.message.contains('cannot lock ref')
    }

    def "FR7: an unreadable tracking tip refuses before any ref is written"() {
        given:
        git.answer([
            'rev-parse',
            '--verify',
            '--quiet',
            TRACKING
        ], 1)

        when:
        adoption().adopt(CLONE, TASK, new BranchLocation.RemoteTracking(TRACKING))

        then:
        thrown(BranchTipUnavailableException)
        transactions().isEmpty()
    }

    // Scenario: git/git-task-persistence / A branch that exists nowhere is not adopted
    def "FR7: #location answers #expected and writes no ref"() {
        when:
        def answer = adoption().adopt(CLONE, TASK, location)

        then:
        answer == expected
        git.calls().isEmpty()

        where:
        location | expected
        new BranchLocation.Local(HEAD) | new BranchAdoption.AlreadyLocal(TASK)
        new BranchLocation.NotFound() | new BranchAdoption.Nowhere()
    }

    def "FR7: an unestablished or refused location propagates as today: #location"() {
        when:
        adoption().adopt(CLONE, TASK, location)

        then:
        def failure = thrown(expected)
        failure.message.contains(TASK)
        git.calls().isEmpty()

        where:
        location | expected
        new BranchLocation.Unavailable(UntrustedText.factory('no answer')) | BranchLocationUnavailableException
        new BranchLocation.Refused(UntrustedText.factory('bad object')) | BranchLocationRefusedException
    }
}
