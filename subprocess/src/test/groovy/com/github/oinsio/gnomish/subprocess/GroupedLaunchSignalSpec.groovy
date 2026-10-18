package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.sandbox.environment.GroupLeader
import com.github.oinsio.gnomish.subprocess.ProcessGroupSweep.Answer
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR21, design D19 of fix-terminal-receipt-convergence: the group sweep's {@code kill}, started
 * ungrouped in the launch owner's file — {@code kill -<signal> -- -<group>}, answered by its exit
 * code alone, never by the text of its error, and never spelled for a group id of one or less. The
 * groups are real; the signaller is the host's {@code /bin/kill}, or a stand-in where the subject
 * is a {@code kill} that cannot start or does not end.
 */
class GroupedLaunchSignalSpec extends Specification implements FakeBinaries {

    @TempDir
    Path work

    // Scenario: lifecycle/subprocess-supervision / A failed launch is never swept
    def "FR21: the owner's kill refuses a group id of #group before spelling it"() {
        when:
        new GroupedLaunch(work).signalGroup('0', group)

        then:
        IllegalArgumentException refused = thrown()
        refused.message.contains(String.valueOf(group))

        where:
        group << [0L, 1L, -1L]
    }

    def "FR21: the owner's kill reads its answer from the exit code: a live group answers, a gone one refuses"() {
        given: 'a live group, and the id of one that is empty'
        Process sleeper = GroupLeader.start(work)
        Process gone = launched('true')
        gone.waitFor(10, TimeUnit.SECONDS)
        GroupedLaunch owner = new GroupedLaunch(work)

        expect:
        owner.signalGroup('0', sleeper.pid()) == Answer.ANSWERED
        owner.signalGroup('0', gone.pid()) == Answer.REFUSED

        cleanup:
        sleeper?.destroyForcibly()
    }

    def "FR21: a kill that cannot be started is no answer"() {
        given:
        GroupedLaunch owner = new GroupedLaunch(work, null, new HostLauncher('Linux', null, work.resolve('no-kill')))

        expect:
        owner.signalGroup('0', 4242) == Answer.UNSENT
    }

    def "FR21: a kill that does not end within its bound is no answer"() {
        given: 'a signaller that never exits'
        GroupedLaunch owner = new GroupedLaunch(work, null, new HostLauncher('Linux', null, fakeBinary('stubborn')))

        expect:
        owner.signalGroup('0', 4242) == Answer.UNSENT
    }

    private Process launched(String... command) {
        ProcessBuilder builder = new ProcessBuilder(command)
        TestChildEnvironment.cleared(builder)
        ProcessLaunch.grouped(work).start(builder, StdinSource.NONE)
    }
}
