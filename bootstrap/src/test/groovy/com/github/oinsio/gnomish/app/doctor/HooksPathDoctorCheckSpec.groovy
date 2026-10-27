package com.github.oinsio.gnomish.app.doctor

import com.github.oinsio.gnomish.adapter.git.AdversarialGitConfig
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.FixtureGitConfig
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.HooksPathLeftover
import com.github.oinsio.gnomish.adapter.git.HooksPathVerdict
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.project.ProjectResolution
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR8 of add-doctor-command (design D6); UX2 of own-git-invocation-policy: on a registered clone
 * the hooks-path line is {@code HooksPathLeftover}'s verdict over the factory's runner, the policy in
 * force — the leftover of an earlier version warns with the owner's unset line, an operator's own
 * path or none is ok with no unset line, a read git refuses warns — and nothing is written.
 */
class HooksPathDoctorCheckSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    private final GitProcessRunner runner = new GitProcessRunner()
    private final HooksPathDoctorCheck check = new HooksPathDoctorCheck(new HooksPathLeftover(runner))

    def setup() {
        AdversarialGitConfig.assertInEffect(runner)
    }

    // Scenario: cli/operator-doctor / Leftover hooks path of an earlier version
    def "FR8, UX2 of own-git-invocation-policy: the leftover line warns with the unset fix, and the configuration is unchanged"() {
        given:
        Path clone = initWorkingRepo(tempDir)
        String leftover = clone.resolve('.git').resolve('gnomish-empty-hooks').toString()
        FixtureGitConfig.set(clone, 'core.hooksPath', leftover)
        byte[] before = Files.readAllBytes(clone.resolve('.git/config'))

        when:
        def verdicts = check.run(on(clone))

        then:
        check.id() == DoctorCheckId.HOOKS_PATH
        verdicts.size() == 1
        verdicts[0].status() == DoctorStatus.WARN
        verdicts[0].detail().contains(leftover)
        verdicts[0].fix() == HooksPathVerdict.FIX
        verdicts[0].fix() == 'git config --local --unset core.hooksPath'
        Files.readAllBytes(clone.resolve('.git/config')) == before
        assertNothingPlantedRan(clone)
    }

    // Scenario: cli/operator-doctor / An operator's own hooks path is not reported
    def "FR8, UX2 of own-git-invocation-policy: #description is ok, with no unset line"() {
        given:
        Path clone = initWorkingRepo(tempDir)
        if (value == null) {
            FixtureGitConfig.unset(clone, 'core.hooksPath')
        } else {
            FixtureGitConfig.set(clone, 'core.hooksPath', value)
        }

        expect:
        check.run(on(clone)) == [
            new DoctorVerdict(DoctorCheckId.HOOKS_PATH, DoctorStatus.OK, 'no hooks path an earlier factory version wrote', null)
        ]

        where:
        description | value
        'an unset local key' | null
        "an operator's own path" | '/srv/my-hooks'
    }

    def "FR8, UX1: a read git refuses warns, naming what git said, never ok, with the git line to run"() {
        given: 'a registered directory that is no git working tree'
        Path notAClone = Files.createDirectories(tempDir.resolve('plain'))

        when:
        def verdicts = check.run(on(notAClone))

        then:
        verdicts*.status() == [DoctorStatus.WARN]
        verdicts[0].detail().startsWith("the clone's local hooks path could not be read: ")
        verdicts[0].fix() == "run git -C ${notAClone} config --local --get core.hooksPath to see what git answers, fix what it names, then run gnomish doctor again".toString()
    }

    def "FR2: outside a project the check is skipped"() {
        expect:
        check.run(DoctorSpecSupport.withoutProject())*.status() == [DoctorStatus.SKIPPED]
    }

    private DoctorContext on(Path clone) {
        def registered = RegisteredCloneFixture.unregistered(tempDir.resolve('home'), clone)
        DoctorContext.of(new ProjectResolution.Registered(registered), {
            new PipelineReading.Unloaded(['not read'], DoctorSpecSupport.LAW_FIX)
        })
    }
}
