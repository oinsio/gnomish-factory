package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitVersionCheck
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.port.git.GitVersionRefusedException
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR10, design D8 of own-git-transfer-argv, at the command layer: the floor check sits in {@code
 * ManualRunRunner.run} before any subcommand dispatches, so {@code run}, {@code take} and {@code
 * serve} are all refused by a git below the floor before a transfer, a claim or a tracker write.
 * The evidence is the fake git's own record — every git-backed port of the assembly, the task-git
 * bundle included, runs through it, and it sees {@code --version} alone — and a tracker that
 * records every interaction and receives none.
 */
class GitVersionFloorSpec extends Specification implements AppAssemblyFixture {

    @TempDir
    Path projectDir

    @TempDir
    Path worktreesRoot

    @TempDir
    Path homeDir

    private LogCaptureSupport logs

    def setup() {
        logs = LogCaptureSupport.attach(GitVersionCheck, Level.ERROR)
    }

    def cleanup() {
        logs.detach()
    }

    def "FR10: '#subcommand' is refused below the floor before any transfer, claim or tracker write"() {
        given: 'a git one release below the floor that records every invocation'
        def record = homeDir.resolve('argv.log')
        def git = new GitProcessRunner(oldGit(record).toString())

        and: 'a tracker that records every interaction'
        Tracker tracker = Mock()

        and: 'the command runner over that git and that tracker'
        def properties = testProperties()
        def runner = newManualRunRunner(
                worktreesRoot,
                homeDir,
                new SandboxProperties(null, null, null, null, null, null, false, null, null, null, null),
                new BindingProperties('host', [:]),
                TaskGitFixture.real(git),
                properties,
                new BoardCommand(Clock.systemUTC(), properties, [:], MapSecretsProvider.NONE,
                TrackerValidatorStub.plainSource(), LiveConsoleIO.onStdout()),
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
        Files.readAllLines(record) == ['--version']

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

    /** An executable git stand-in reporting 2.44.0 and appending every argv to {@code record}. */
    private Path oldGit(Path record) {
        def script = homeDir.resolve('old-git.sh')
        script.toFile().text = "#!/bin/sh\nprintf '%s\\n' \"\$@\" >> '${record}'\necho 'git version 2.44.0'\n"
        script.toFile().executable = true
        script
    }
}
