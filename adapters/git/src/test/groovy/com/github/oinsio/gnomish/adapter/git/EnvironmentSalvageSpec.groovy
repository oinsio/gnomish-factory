package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.git.GitSalvageFailedException
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.ProcessStartException
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6 of add-sandbox-core, "Salvage of interrupted rounds" of
 * git-task-persistence: {@link EnvironmentSalvage}'s leftover probe answers
 * from the in-box working copy — dirty means salvageable, clean means nothing
 * to do — and a lost environment degrades to "nothing reachable to salvage"
 * instead of throwing or pretending there is work to commit.
 */
class EnvironmentSalvageSpec extends Specification implements BareGitRepoFixture {

    static final String BRANCH = TaskIdSanitizer.branchName('SALV-1')

    @TempDir
    Path tempDir

    Path cloneDir

    private LocalBoxEnvironment materializedBox() {
        cloneDir = initWorkingRepo(tempDir, 'factory-clone')
        new File(cloneDir.toFile(), 'seed.txt').text = 'seed'
        commitAll(cloneDir)
        gitOutput(cloneDir, 'branch', BRANCH)
        def box = new LocalBoxEnvironment(cloneDir, Files.createDirectories(tempDir.resolve('box')))
        box.materialize(BRANCH, null)
        box
    }

    def "FR6: an uncommitted in-box change is a leftover to salvage"() {
        given:
        def box = materializedBox()
        new File(box.workingCopy.toFile(), 'work.txt').text = 'interrupted round'

        expect:
        new EnvironmentSalvage(box, ClaimEpochSource.NONE).hasLeftovers()
    }

    def "FR6: a clean in-box working copy has no leftovers"() {
        expect:
        !new EnvironmentSalvage(materializedBox(), ClaimEpochSource.NONE).hasLeftovers()
    }

    // FR5 of harden-logging-observability, the environment half of WorktreeSalvage's degrade
    // specs: every one of this class's four lost-environment fallbacks returns a degraded answer,
    // and a degraded answer that leaves no trace is indistinguishable from a healthy one.
    def "FR5: a dead environment probes as having nothing reachable to salvage, and says so"() {
        given: 'an environment whose exec cannot even start'
        def dead = [exec: { ExecCommand command ->
                throw new ProcessStartException('container gone', new IOException('no runtime'))
            }] as TaskExecutionEnvironment
        def logs = LogCaptureSupport.attach(EnvironmentSalvage)

        when: 'the probe degrades to false — resume continues from the last harvested state'
        def leftovers = new EnvironmentSalvage(dead, ClaimEpochSource.NONE).hasLeftovers()
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        !leftovers

        and: 'the unreachable environment is named at WARN, with the cause attached'
        def warnings = events.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.contains('salvage probe could not reach the environment')
        warnings[0].throwableProxy.className == ProcessStartException.name
    }

    // FR5: the environment died between the probe and the commit — the leftovers are real but
    // unreachable, so the round's tail is lost and resume continues from the last harvest. Silent,
    // it would read as a salvage that had nothing to do.
    def "FR5: salvage() warns with the taskId when the environment dies before the commit"() {
        given: 'a box whose status still answers but whose commit cannot start'
        cloneDir = initWorkingRepo(tempDir, 'factory-clone-dying')
        new File(cloneDir.toFile(), 'seed.txt').text = 'seed'
        commitAll(cloneDir)
        gitOutput(cloneDir, 'branch', BRANCH)
        def dying = new LocalBoxEnvironment(cloneDir, Files.createDirectories(tempDir.resolve('dying-box'))) {
                    @Override
                    ExecHandle exec(ExecCommand command) {
                        if (command.command()[2].contains('commit -m')) {
                            throw new ProcessStartException('container gone', new IOException('no runtime'))
                        }
                        super.exec(command)
                    }
                }
        dying.materialize(BRANCH, null)
        new File(dying.workingCopy.toFile(), 'work.txt').text = 'interrupted round'
        def logs = LogCaptureSupport.attach(EnvironmentSalvage)

        when:
        new EnvironmentSalvage(dying, ClaimEpochSource.NONE).salvage('SALV-LOST')
        def events = List.copyOf(logs.list)
        logs.detach()

        then: 'the loss does not propagate — resume continues from the last harvested state'
        noExceptionThrown()

        and: 'but it is on the record, findable by the task it cost'
        def warnings = events.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.contains('taskId=SALV-LOST')
        warnings[0].formattedMessage.contains('environment lost')
        warnings[0].throwableProxy.className == ProcessStartException.name

        and: 'nothing reached the factory clone'
        gitOutput(cloneDir, 'log', '--format=%s', 'refs/heads/' + BRANCH) == 'init'
    }

    // FR5, mirrored on WorktreeSalvage's own discard degrade path: a discard that could not run
    // leaves the very leftovers it exists to remove, so the next round starts on a working copy
    // nobody expects. The taskId rides the MDC here, as it does for every line of a working task.
    def "FR5: discard() warns that the leftovers stay when the environment is gone"() {
        given:
        def dead = [exec: { ExecCommand command ->
                throw new ProcessStartException('container gone', new IOException('no runtime'))
            }] as TaskExecutionEnvironment
        def logs = LogCaptureSupport.attach(EnvironmentSalvage)

        when:
        new EnvironmentSalvage(dead, ClaimEpochSource.NONE).discard()
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        noExceptionThrown()

        and:
        def warnings = events.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.contains('discard skipped')
        warnings[0].formattedMessage.contains('leftovers stay in the box')
        warnings[0].throwableProxy.className == ProcessStartException.name
    }

    def "FR6: salvage() commits and harvests a leftover into the factory clone"() {
        given:
        def box = materializedBox()
        new File(box.workingCopy.toFile(), 'work.txt').text = 'interrupted round'
        def tipBefore = gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH)

        when:
        new EnvironmentSalvage(box, ClaimEpochSource.NONE).salvage('SALV-1')

        then: 'the leftover is committed in-box and harvested to the factory clone'
        def tipAfter = gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH)
        tipAfter != tipBefore
        gitOutput(cloneDir, 'log', '-1', '--format=%s', tipAfter) == 'gnomish: salvage'
        gitOutput(cloneDir, 'show', tipAfter + ':work.txt') == 'interrupted round'

        and: 'the box is clean afterwards'
        !new EnvironmentSalvage(box, ClaimEpochSource.NONE).hasLeftovers()
    }

    // FR5, design D11 of harden-task-branch-contract: the in-box salvage applies the SAME
    // ownership policy as the host worktree salvage — factory-owned .gnomish-task/ paths come
    // from the in-box HEAD, only gnome-owned work files ride the salvage commit.
    def "FR5: salvage() restores factory-owned files from the in-box tip instead of committing them"() {
        given: 'a box whose HEAD carries a recorded state.json'
        def box = materializedBox()
        def work = box.workingCopy
        Files.createDirectories(work.resolve('.gnomish-task/decisions'))
        Files.writeString(work.resolve('.gnomish-task/state.json'), '{"recorded":true}')
        gitOutput(work, 'add', '-A')
        gitOutput(work, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'started')
        new EnvironmentSalvage(box, ClaimEpochSource.NONE).salvage('SALV-STATE')

        and: 'a dying round left a truncated state.json, gnome work and a decision file behind'
        Files.writeString(work.resolve('.gnomish-task/state.json'), '{ truncated')
        Files.writeString(work.resolve('.gnomish-task/decisions/build-a0.json'), '{"asked":true}')
        new File(work.toFile(), 'work.txt').text = 'interrupted round'

        when:
        new EnvironmentSalvage(box, ClaimEpochSource.NONE).salvage('SALV-4')

        then: 'the harvested tip carries the gnome work and the tip\'s state.json, not the dirty one'
        def tip = gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH)
        gitOutput(cloneDir, 'show', tip + ':work.txt') == 'interrupted round'
        gitOutput(cloneDir, 'show', tip + ':.gnomish-task/state.json') == '{"recorded":true}'

        and: 'the gnome-writable decisions path is salvaged like any work file'
        gitOutput(cloneDir, 'show', tip + ':.gnomish-task/decisions/build-a0.json') == '{"asked":true}'
    }

    // FR5, the container half of WorktreeSalvageSpec's twin scenario: a restore that FAILED must
    // fail the salvage. Tolerating its exit status lets the `git add -A` below stage the dying
    // round's half-written state.json into the salvage commit — the exact outcome the restore
    // exists to prevent. Only "the tip carries no state directory" is tolerated, and that is
    // guarded, not swallowed.
    def "FR5: salvage() fails rather than committing a factory-owned file the in-box restore could not put back"() {
        given: 'a box whose HEAD carries a recorded state.json, plus a dying round\'s truncated one'
        def box = materializedBox()
        def work = box.workingCopy
        Files.createDirectories(work.resolve('.gnomish-task/decisions'))
        Files.writeString(work.resolve('.gnomish-task/state.json'), '{"recorded":true}')
        Files.writeString(work.resolve('.gnomish-task/decisions/.keep'), '')
        gitOutput(work, 'add', '-A')
        gitOutput(work, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'started')
        Files.writeString(work.resolve('.gnomish-task/state.json'), '{ truncated')
        new File(work.toFile(), 'work.txt').text = 'interrupted round'

        and: 'the state directory is unwritable, so the in-box checkout cannot unlink the truncated file'
        def stateDir = work.resolve('.gnomish-task')
        def original = Files.getPosixFilePermissions(stateDir)
        Files.setPosixFilePermissions(
                stateDir, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))

        when:
        new EnvironmentSalvage(box, ClaimEpochSource.NONE).salvage('SALV-5')

        then: 'the failed restore is reported, not absorbed into a salvage commit'
        def ex = thrown(GitSalvageFailedException)
        ex.message.contains('SALV-5')

        and: 'and the truncated state.json never reached the in-box tip'
        gitOutput(work, 'show', 'HEAD:.gnomish-task/state.json') == '{"recorded":true}'

        cleanup:
        Files.setPosixFilePermissions(stateDir, original)
    }

    // FR5, design D16 of harden-task-branch-contract: factory-invoked in-box git runs with hooks
    // disabled at argv level. `git checkout HEAD -- <paths>` invokes post-checkout (flag=0, a file
    // checkout), so without `-c core.hooksPath=` a gnome-planted hook would execute inside the box
    // during the factory's own salvage restore.
    def "FR5: the salvage restore checkout runs no gnome-planted hook"() {
        given: 'a box whose HEAD carries a recorded state.json'
        def box = materializedBox()
        def work = box.workingCopy
        Files.createDirectories(work.resolve('.gnomish-task'))
        Files.writeString(work.resolve('.gnomish-task/state.json'), '{"recorded":true}')
        gitOutput(work, 'add', '-A')
        gitOutput(work, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'started')

        and: 'a gnome-planted post-checkout hook that would betray itself outside the working copy'
        def marker = tempDir.resolve('hook-ran')
        def hook = work.resolve('.git/hooks/post-checkout')
        Files.writeString(hook, "#!/bin/sh\ntouch '" + marker + "'\n")
        Files.setPosixFilePermissions(hook, EnumSet.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))

        and: 'a dying round left a dirty state.json and gnome work behind, so the restore checkout runs'
        Files.writeString(work.resolve('.gnomish-task/state.json'), '{ truncated')
        new File(work.toFile(), 'work.txt').text = 'interrupted round'

        when:
        new EnvironmentSalvage(box, ClaimEpochSource.NONE).salvage('SALV-HOOK')

        then: 'the salvage succeeded without ever running the hook'
        Files.notExists(marker)
        gitOutput(cloneDir, 'show', 'refs/heads/' + BRANCH + ':work.txt') == 'interrupted round'
    }

    // NFR-S1, FR6: script text and data stay apart. The salvage script is a fixed constant and the
    // state directory, the pathspec and the stamped commit message reach it as positional
    // arguments, so nothing the factory computes is ever concatenated into shell source running
    // inside the box. Pinning it here rather than trusting the message's shape: the message gains
    // a claim-epoch trailer (FR13) and could gain more, and the moment a dynamic value is quoted
    // into the script instead, a metacharacter in it changes the command that runs.
    def "NFR-S1: the salvage commit passes its paths and message as arguments, never inside the script"() {
        given: 'a box that records every exec argv on its way through to the real one'
        cloneDir = initWorkingRepo(tempDir, 'factory-clone-argv')
        new File(cloneDir.toFile(), 'seed.txt').text = 'seed'
        commitAll(cloneDir)
        gitOutput(cloneDir, 'branch', BRANCH)
        List<ExecCommand> seen = []
        def box = new LocalBoxEnvironment(cloneDir, Files.createDirectories(tempDir.resolve('argv-box'))) {
                    @Override
                    ExecHandle exec(ExecCommand command) {
                        seen << command
                        super.exec(command)
                    }
                }
        box.materialize(BRANCH, null)
        new File(box.workingCopy.toFile(), 'work.txt').text = 'interrupted round'

        when:
        new EnvironmentSalvage(box, ClaimEpochSource.NONE).salvage('SALV-ARGV')

        then: 'the commit ran through sh -c with a script that names none of them'
        ExecCommand commit = seen.find { it.command()[2].contains('commit -m') }
        commit != null
        commit.command()[0] == 'sh'
        commit.command()[1] == '-c'
        !commit.command()[2].contains('gnomish: salvage')
        !commit.command()[2].contains('.gnomish-task')

        and: 'each of them arrived as its own positional argument instead'
        def positional = commit.command().drop(3)
        positional.contains('gnomish: salvage')
        positional.contains('.gnomish-task')
        positional.contains(':(exclude).gnomish-task/decisions')

        and: 'and the salvage still did its job'
        gitOutput(cloneDir, 'log', '-1', '--format=%s', 'refs/heads/' + BRANCH) == 'gnomish: salvage'
    }

    def "FR6: salvage() is a no-op on a clean box — no commit, no harvest"() {
        given:
        def box = materializedBox()
        def tipBefore = gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH)

        when:
        new EnvironmentSalvage(box, ClaimEpochSource.NONE).salvage('SALV-2')

        then:
        gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH) == tipBefore
    }

    def "FR6: salvage() throws GitSalvageFailedException when the in-box commit fails, and never harvests"() {
        given: 'a leftover, plus the git index lock already held by another process in-box'
        def box = materializedBox()
        new File(box.workingCopy.toFile(), 'work.txt').text = 'interrupted round'
        def tipBefore = gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH)
        new File(box.workingCopy.toFile(), '.git/index.lock').text = 'held by another process'

        when:
        new EnvironmentSalvage(box, ClaimEpochSource.NONE).salvage('SALV-3')

        then:
        def ex = thrown(GitSalvageFailedException)
        ex.message.contains('SALV-3')

        and: 'the factory clone tip is unchanged — the failed commit was never harvested'
        gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH) == tipBefore
    }

    def "FR6: a harvest that fails tolerantly after a successful salvage commit does not propagate"() {
        given: 'a box whose harvest always fails with a plain (non-refusal) transport error'
        def cloneDir = initWorkingRepo(tempDir, 'factory-clone-flaky')
        new File(cloneDir.toFile(), 'seed.txt').text = 'seed'
        commitAll(cloneDir)
        gitOutput(cloneDir, 'branch', BRANCH)
        def flakyBox = new LocalBoxEnvironment(cloneDir, Files.createDirectories(tempDir.resolve('flaky-box'))) {
                    @Override
                    void harvest() {
                        throw new HarvestFailedException(BRANCH, 'simulated transport failure')
                    }
                }
        flakyBox.materialize(BRANCH, null)
        new File(flakyBox.workingCopy.toFile(), 'work.txt').text = 'interrupted round'
        def logs = LogCaptureSupport.attach(EnvironmentSalvage)

        when:
        new EnvironmentSalvage(flakyBox, ClaimEpochSource.NONE).salvage('SALV-4')
        def events = List.copyOf(logs.list)
        logs.detach()

        then: 'no exception propagates — the WARN path is taken and resume continues from the last harvested state'
        noExceptionThrown()

        and: 'FR5: the commit that never reached the factory clone is named, with its task and cause'
        def warnings = events.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.contains('salvage harvest failed for taskId=SALV-4')
        warnings[0].throwableProxy.className == HarvestFailedException.name

        and: 'the in-box commit did land even though the harvest afterward failed'
        !new EnvironmentSalvage(flakyBox, ClaimEpochSource.NONE).hasLeftovers()

        and: 'but the factory clone never saw it, since the harvest failed'
        gitOutput(cloneDir, 'log', '--format=%s', 'refs/heads/' + BRANCH) == 'init'
    }
}
