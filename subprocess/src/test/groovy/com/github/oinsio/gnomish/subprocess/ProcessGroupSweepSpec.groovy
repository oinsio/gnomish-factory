package com.github.oinsio.gnomish.subprocess

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.subprocess.ProcessGroupSweep.Answer
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR21, M15, design D19 of fix-terminal-receipt-convergence: after the exit the launch owner's
 * group is swept — {@code kill -KILL -- -<pid>} while the zero-signal probe is answered, at most
 * five rounds — so a descendant the child lost to reparenting, which no snapshot of the tree lists,
 * dies with the invocation. The groups are real on purpose: what is measured is what the OS does
 * with a group whose leader is gone. The round logic is pinned on a scripted signal, which answers
 * as {@code kill}'s exit code would, since a fork between two rounds cannot be timed on demand.
 */
class ProcessGroupSweepSpec extends Specification implements FakeBinaries {

    @TempDir
    Path work

    // Scenario: lifecycle/subprocess-supervision / A reparented orphan dies with the invocation
    def "FR21, M15: the holder exit-leaky leaves behind is alive before the sweep and gone after it"() {
        given: 'a child that exits cleanly and leaves sleep 600 holding its stdout'
        Path pidFile = work.resolve('holder.pid')
        Process process = launched(fakeBinary('exit-leaky').toString(), pidFile.toString())
        process.waitFor(10, TimeUnit.SECONDS)
        ProcessHandle holder = pidOf(pidFile)

        expect: 'the exit left the holder alive, and no snapshot of the child lists it'
        holder.isAlive()
        process.descendants().toList().isEmpty()

        when:
        GroupSweep swept = new ProcessGroupSweep().sweep(process)

        then: 'the holder is gone, and the sweep says it found a survivor'
        holder.onExit().get(10, TimeUnit.SECONDS)
        !holder.isAlive()
        swept.rounds() >= 1
        swept.foundSurvivors()

        cleanup:
        killQuietly(holder)
    }

    // Scenario: lifecycle/subprocess-supervision / A reparented orphan dies with the invocation
    def "FR21: a child that outlived its parent, signal-ignoring and reparented, is gone after the sweep"() {
        given: 'parent.sh, whose TERM-ignoring child and its sleep are up'
        Path pidFile = work.resolve('child.pid')
        Process process = launched(fakeBinary('parent').toString(), pidFile.toString())
        eventually('the parent has recorded its child') {
            Files.exists(pidFile) && !Files.readString(pidFile).isBlank()
        }
        ProcessHandle child = pidOf(pidFile)
        eventually('the child has forked its sleep') {
            !child.descendants().toList().isEmpty()
        }
        List<ProcessHandle> orphans = [child] + child.descendants().toList()

        and: 'the parent alone is killed, so its child is reparented away from it'
        process.toHandle().destroyForcibly()
        process.waitFor(10, TimeUnit.SECONDS)

        expect: 'the orphans outlived the parent, and its snapshot no longer lists them'
        orphans.every { it.isAlive() }
        process.descendants().toList().isEmpty()

        when:
        new ProcessGroupSweep().sweep(process)

        then:
        orphans.every { it.onExit().get(10, TimeUnit.SECONDS) != null }

        cleanup:
        orphans?.each { killQuietly(it) }
    }

    def "FR21: an empty group ends the sweep on its first probe"() {
        given: 'a child that leaves nothing behind, already exited'
        Process process = launched('true')
        process.waitFor(10, TimeUnit.SECONDS)
        List<String> sent = []
        ProcessProbes host = new ProcessProbes(GroupedLaunch.groupSignal(), sent)

        when:
        GroupSweep swept = new ProcessGroupSweep(host).sweep(process)

        then: 'one probe, refused by the real kill, and no round'
        sent == [
            "0 ${process.pid()}".toString()
        ]
        swept == GroupSweep.NOTHING
        !swept.foundSurvivors()
    }

    // Scenario: lifecycle/subprocess-supervision / The sweep repeats until nothing answers
    def "FR21: the sweep repeats while the probe is answered and ends when it is refused"() {
        given: 'a group whose probe is answered twice — a member forked between the rounds — then refused'
        List<String> sent = []
        ProcessProbes signal = new ProcessProbes([
            Answer.ANSWERED,
            Answer.ANSWERED,
            Answer.REFUSED
        ], sent)

        when:
        GroupSweep swept = new ProcessGroupSweep(signal).sweep(leader(4242))

        then: 'a round after each answered probe, and the group judged empty by the refusal'
        sent == [
            '0 4242',
            'KILL 4242',
            '0 4242',
            'KILL 4242',
            '0 4242'
        ]
        swept == new GroupSweep(2, true)
        swept.foundSurvivors()
    }

    // Scenario: lifecycle/subprocess-supervision / The sweep repeats until nothing answers
    def "FR21: the sweep ends within five rounds when the probe never stops answering"() {
        given:
        List<String> sent = []
        ProcessProbes signal = new ProcessProbes([Answer.ANSWERED] * 7, sent)

        when:
        GroupSweep swept = new ProcessGroupSweep(signal).sweep(leader(4242))

        then: 'five rounds, each followed by its probe, and the group not cleared'
        sent.count { it.startsWith('KILL') } == ProcessGroupSweep.MAX_ROUNDS
        sent.size() == 2 * ProcessGroupSweep.MAX_ROUNDS + 1
        swept == new GroupSweep(5, false)
        swept.foundSurvivors()
    }

    def "FR21: a probe that could not be sent ends the sweep uncleared, with nothing killed"() {
        given:
        List<String> sent = []

        when:
        GroupSweep swept = new ProcessGroupSweep(new ProcessProbes([Answer.UNSENT], sent)).sweep(leader(4242))

        then: 'no round, and the waiter still hears of it'
        sent == ['0 4242']
        swept == new GroupSweep(0, false)
        swept.foundSurvivors()
        swept.phrase().contains('rounds=0, cleared=false')
    }

    // Scenario: lifecycle/subprocess-supervision / A failed launch is never swept
    def "FR21: no group is signalled for a pid of #pid, and the first group id that names a child is"() {
        given:
        List<String> sent = []

        when:
        GroupSweep swept = new ProcessGroupSweep(new ProcessProbes([Answer.REFUSED], sent)).sweep(leader(pid))

        then:
        sent == expected
        swept == GroupSweep.NOTHING

        where:
        pid || expected
        0 || []
        1 || []
        2 || ['0 2']
    }

    private Process launched(String... command) {
        ProcessBuilder builder = new ProcessBuilder(command)
        TestChildEnvironment.cleared(builder)
        ProcessLaunch.grouped(work).start(builder, StdinSource.NONE)
    }

    private static ProcessHandle pidOf(Path pidFile) {
        ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow()
    }

    /** A process that names {@code pid} as its group and reaches no real process: the signal is scripted. */
    private static Process leader(long pid) {
        new FakeProcess().leading(pid)
    }
}
