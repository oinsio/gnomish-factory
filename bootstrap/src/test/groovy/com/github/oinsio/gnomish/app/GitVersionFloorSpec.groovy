package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitVersionCheck
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.port.git.GitVersionRefusedException
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.gittransfer.GitInvocation
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.testfixtures.standin.StandInLog
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
/**
 * FR10, design D8 of own-git-transfer-argv, at the command layer: the floor check sits in {@code
 * ManualRunRunner.run} before any subcommand dispatches, so {@code run}, {@code take} and {@code
 * serve} are all refused by a git below the floor before a transfer, a claim or a tracker write.
 * The evidence is the fake git's own record — every git-backed port of the assembly, the task-git
 * bundle included, runs through it, and it sees {@code --version} alone — and a tracker that
 * records every interaction and receives none. The refusing git reports 2.55.2, one release below
 * the factory's one floor (2.56.0, design D9 of add-sandbox-base-image), so the check is shown to
 * judge by that floor and not by the lower box floor it once stood beside (FR10, design D11 of
 * own-git-invocation-policy).
 */
class GitVersionFloorSpec extends Specification implements AppAssemblyFixture {

    @TempDir
    Path projectDir

    @TempDir
    Path clonePath

    @TempDir
    Path homeDir

    private LogCaptureSupport logs

    def setup() {
        logs = LogCaptureSupport.attach(GitVersionCheck, Level.ERROR)
    }

    def cleanup() {
        logs.detach()
    }

    // Scenario: git/git-transfer / Old git is refused before any work
    def "FR10: '#subcommand' is refused below the floor before any transfer, claim or tracker write"() {
        given: 'a git one release below the factory\'s floor that records every invocation — 2.55.x (FR10 of own-git-invocation-policy)'
        def oldGit = StandIn.link(homeDir.resolve('old-git'), 'version-2-55')
        def git = new GitProcessRunner(oldGit.toString())

        and: 'a tracker that records every interaction'
        Tracker tracker = Mock()

        and: 'the command runner over that git and that tracker'
        def properties = testProperties()
        def runner = newManualRunRunner(
                clonePath,
                homeDir,
                new SandboxProperties(null, null, null, null, null, null, false, null, null, null, null),
                new BindingProperties('host', [:]),
                TaskGitFixture.real(git),
                properties,
                new TrackerWiring([:], MapSecretsProvider.NONE, TrackerValidatorStub.plainSource(), VirtualTimeEquipment.create()),
                [github: fakeFactory(tracker)],
                new GitVersionCheck(git))

        when:
        String[] resolvedArguments = arguments.collect { String arg ->
            arg.replace('PROJECT', projectDir.toString())
        }
        runner.run(takeArgs(resolvedArguments))

        then: 'the process ends on the precondition'
        thrown(GitVersionRefusedException)

        and: 'git was asked its version and nothing else'
        StandInLog.blocks(oldGit).collect {
            StandInLog.argv(it)
        } == [
            GitInvocation.configPairs() + ['--version']
        ]

        and: 'the tracker saw nothing'
        0 * tracker._

        and: 'the one ERROR line is the check\'s own'
        logs.list.size() == 1

        where: 'PROJECT stands for the project directory, which only the feature body knows'
        subcommand | arguments
        'run' | [
            '--dir=PROJECT',
            '--task=do the thing'
        ]
        'take' | [
            'take',
            'PROJ-1',
            '--dir=PROJECT'
        ]
        'serve' | ['serve', '--dir=PROJECT']
    }
}
