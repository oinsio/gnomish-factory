package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitVersionCheck
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.doctor.DoctorCheckId
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.gittransfer.GitVersion
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Path
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR7, design D2 of add-doctor-command: {@code ManualRunRunner} dispatches {@code doctor} before
 * the git floor refusal every other subcommand passes through, so a git below the floor is a check
 * {@code doctor} reports — through {@code GitVersionCheck.assess()}, the decision the refusal is
 * built on — and not the end of the process. {@code GitVersionFloorSpec} pins the other half: run,
 * take and serve are still refused first.
 *
 * <p>{@link #REPORTED} names the checks the command reports; every check the command gains is added
 * there, so the below-the-floor run keeps proving that nothing stops before the last of them.
 */
class DoctorDispatchSpec extends Specification implements AppAssemblyFixture {

    /**
     * The checks {@code doctor} reports without {@code --online}, by the name its line carries:
     * every one but the online tracker probe (task 3.2; FR3).
     */
    private static final List<String> REPORTED = DoctorCheckId.values().findAll {
        !it.online()
    }*.displayName()

    @TempDir
    Path clonePath

    @TempDir
    Path homeDir

    private LogCaptureSupport logs
    private PrintStream originalOut
    private ByteArrayOutputStream out

    def setup() {
        logs = LogCaptureSupport.attach(GitVersionCheck, Level.ERROR)
        originalOut = System.out
        out = new ByteArrayOutputStream()
        System.out = new PrintStream(out, true, 'UTF-8')
    }

    def cleanup() {
        System.out = originalOut
        logs.detach()
    }

    // Scenario: cli/operator-doctor / Every readiness check is reported
    // Scenario: git/git-transfer / Doctor reports an old git
    // FR1, FR7, D2: a git below the floor refuses nothing in doctor, and every check is reported
    def "FR7: doctor runs with a git below the floor, refuses nothing and reports every check"() {
        given: 'a git one release below the factory\'s floor'
        def runner = runnerOver(StandIn.link(homeDir.resolve('old-git'), 'version-2-55'))

        when:
        runner.run(new DefaultApplicationArguments('doctor', "--dir=${clonePath}".toString()))

        then: 'the floor is one failed check, not the end of the process: exit code 1 after the report'
        def failed = thrown(DoctorFailedException)
        new RunExitCodeMapper().getExitCode(failed) == 1

        and: "the refusal's ERROR line is never logged"
        logs.list.isEmpty()

        and: 'every check has its line'
        def lines = printed()
        REPORTED.size() == 15
        REPORTED.every { name -> lines.any { it.contains("  ${name}: ") } }

        and: 'the floor is reported as failed, with the floor and its reason'
        lines.contains(("fail  git version floor: git 2.55.2 is below the floor ${GitVersion.FLOOR}: ${GitVersion.FLOOR_REASON}" +
                "; fix: install git ${GitVersion.FLOOR} or later on the factory's PATH").toString())

        and: 'the summary closes the report'
        lines.last().startsWith('summary: ')
    }

    // FR4, FR7, D1: the doctor line is the verdict of the owner the refusal is built on
    def "FR4: doctor reports the git floor verdict of #preset as '#expected'"() {
        given:
        def runner = runnerOver(StandIn.link(homeDir.resolve('git'), preset))

        when:
        DoctorFailedException failure = null
        try {
            runner.run(new DefaultApplicationArguments('doctor', '--online'))
        } catch (DoctorFailedException e) {
            failure = e
        }

        then: 'the floor line'
        printed().any { it.startsWith(expected) }

        and: 'FR6: the run ends with exit code 1 exactly when a line is fail'
        (failure != null) == printed().any { it.startsWith('fail  ') }
        expected.startsWith('ok') || failure != null

        where:
        preset || expected
        'version-2-56-0' || "ok  git version floor: git 2.56.0 meets the floor ${GitVersion.FLOOR}".toString()
        'version-2-55' || 'fail  git version floor: git 2.55.2 is below the floor '
        'version-broken' || 'fail  git version floor: git reported no version (it printed: '
    }

    // Scenario: cli/operator-doctor / JSON for automation
    def "FR5: doctor --json prints one document on stdout, listing every check with its id, status, detail and fix"() {
        given:
        def runner = runnerOver(StandIn.link(homeDir.resolve('git'), 'version-2-55'))

        when:
        runner.run(new DefaultApplicationArguments('doctor', '--json', "--dir=${clonePath}".toString()))

        then:
        thrown(DoctorFailedException)
        def tree = new ObjectMapper().readTree(out.toString('UTF-8'))
        tree.get('version').intValue() == 1
        tree.get('checks')*.get('id')*.textValue().unique() == DoctorCheckId.values().findAll {
            !it.online()
        }*.id()
        tree.get('checks').every {
            it.has('status') && it.has('detail') && it.has('fix')
        }
        def floor = tree.get('checks').find {
            it.get('id').textValue() == 'git-floor'
        }
        floor.get('status').textValue() == 'fail'
        floor.get('fix').textValue() == "install git ${GitVersion.FLOOR} or later on the factory's PATH".toString()
    }

    // FR7, FR8 of fix-operator-blockers: an option doctor does not accept is refused before any check
    def "FR7: doctor refuses an unknown option before running a check"() {
        given:
        def git = StandIn.link(homeDir.resolve('git'), 'version-2-56-0')
        def runner = runnerOver(git)

        when:
        runner.run(new DefaultApplicationArguments('doctor', '--no-such-option=1'))

        then:
        def e = thrown(UsageException)
        e.message == "unknown option --no-such-option for 'gnomish doctor'; accepted: --dir, --online, --json"
        printed().isEmpty()
    }

    private ManualRunRunner runnerOver(Path gitBinary) {
        def git = new GitProcessRunner(gitBinary.toString())
        newManualRunRunner(
                clonePath,
                homeDir,
                new SandboxProperties(null, null, null, null, null, null, false, null, null, null, null),
                new BindingProperties('host', [:]),
                TaskGitFixture.real(git),
                testProperties(),
                new TrackerWiring([:], MapSecretsProvider.NONE, TrackerValidatorStub.plainSource(), VirtualTimeEquipment.create()),
                [:],
                new GitVersionCheck(git))
    }

    private List<String> printed() {
        out.toString('UTF-8').readLines()
    }
}
