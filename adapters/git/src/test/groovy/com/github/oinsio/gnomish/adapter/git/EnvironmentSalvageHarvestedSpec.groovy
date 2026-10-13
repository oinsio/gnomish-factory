package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.git.GitSalvageFailedException
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.ProcessStartException
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13 of tighten-spec-contract (design D13): the in-box salvage answers whether a salvage commit
 * reached the factory clone — the one case its driver registers the tip as a closing commit. Every
 * other outcome (nothing to salvage, a box lost before the commit, a harvest that failed) answers
 * {@code false}, so a tip the gnome left is never registered.
 */
class EnvironmentSalvageHarvestedSpec extends Specification implements BareGitRepoFixture {

    static final String BRANCH = TaskIdSanitizer.branchName('SALV-H')

    @TempDir
    Path tempDir

    Path cloneDir

    private LocalBoxEnvironment box(Closure<LocalBoxEnvironment> make) {
        cloneDir = initWorkingRepo(tempDir, 'factory-clone')
        new File(cloneDir.toFile(), 'seed.txt').text = 'seed'
        commitAll(cloneDir)
        gitOutput(cloneDir, 'branch', BRANCH)
        // The branch starts at a registered closing commit, as STARTED leaves it (design D13).
        gitOutput(cloneDir, 'update-ref', 'refs/gnomish-factory/' + BRANCH.substring('gnomish/'.length()), 'refs/heads/' + BRANCH)
        LocalBoxEnvironment box = make.call(cloneDir, Files.createDirectories(tempDir.resolve('box')))
        box.materialize(BRANCH, null)
        box
    }

    def "FR13: a salvage commit that was made and harvested answers true"() {
        given:
        def box = box { Path clone, Path dir ->
            new LocalBoxEnvironment(clone, dir)
        }
        new File(box.workingCopy.toFile(), 'work.txt').text = 'interrupted round'

        expect:
        new EnvironmentSalvage(box, ClaimEpochSource.NONE, new GitProcessRunner(), cloneDir).salvageHarvested('SALV-H')
    }

    def "FR13: nothing to salvage answers false"() {
        expect:
        !new EnvironmentSalvage(box { Path clone, Path dir ->
            new LocalBoxEnvironment(clone, dir)
        }, ClaimEpochSource.NONE, new GitProcessRunner(), cloneDir)
        .salvageHarvested('SALV-H')
    }

    def "FR13: a box lost before the commit answers false"() {
        given:
        def box = box { Path clone, Path dir ->
            new LocalBoxEnvironment(clone, dir) {
                @Override
                ExecHandle exec(ExecCommand command) {
                    if (command.command()[2].contains('commit -m')) {
                        throw new ProcessStartException('container gone', new IOException('no runtime'))
                    }
                    super.exec(command)
                }
            }
        }
        new File(box.workingCopy.toFile(), 'work.txt').text = 'interrupted round'

        expect:
        LogCaptureSupport.capture(EnvironmentSalvage, Level.INFO) {
            assert !new EnvironmentSalvage(box, ClaimEpochSource.NONE, new GitProcessRunner(), cloneDir).salvageHarvested('SALV-H')
        }
    }

    def "FR13: a salvage commit whose harvest failed answers false"() {
        given:
        def box = box { Path clone, Path dir ->
            new LocalBoxEnvironment(clone, dir) {
                @Override
                void harvest() {
                    throw new HarvestFailedException(BRANCH, UntrustedText.subprocess('transport gone'))
                }
            }
        }
        new File(box.workingCopy.toFile(), 'work.txt').text = 'interrupted round'

        expect:
        LogCaptureSupport.capture(EnvironmentSalvage, Level.INFO) {
            assert !new EnvironmentSalvage(box, ClaimEpochSource.NONE, new GitProcessRunner(), cloneDir).salvageHarvested('SALV-H')
        }
    }

    // Scenario: git/git-task-persistence / Refused content is not durable
    def "FR13: a salvage whose range changes a factory-owned path is refused, and the branch does not move"() {
        given: 'the gnome committed a forged state.json in its box, and left work behind'
        def box = box { Path clone, Path dir ->
            new LocalBoxEnvironment(clone, dir)
        }
        def before = gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH)
        Files.createDirectories(box.workingCopy.resolve('.gnomish-task'))
        Files.writeString(box.workingCopy.resolve('.gnomish-task/state.json'), '{"forged":true}')
        gitOutput(box.workingCopy, 'add', '-A')
        gitOutput(box.workingCopy, 'commit', '-q', '-m', 'gnome')
        new File(box.workingCopy.toFile(), 'work.txt').text = 'interrupted round'

        when:
        new EnvironmentSalvage(box, ClaimEpochSource.NONE, new GitProcessRunner(), cloneDir).salvageHarvested('SALV-H')

        then:
        def ex = thrown(RoundBoundaryViolationException)
        ex.message.contains('.gnomish-task/state.json')
        gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH) == before
    }

    def "FR13: factory-owned dirt alone is no salvage, #gnome"() {
        given: 'a branch carrying state.json, and a box whose only dirt is under the state directory'
        def box = box { Path clone, Path dir ->
            Files.createDirectories(clone.resolve('.gnomish-task'))
            Files.writeString(clone.resolve('.gnomish-task/state.json'), '{"recorded":true}')
            commitAll(clone)
            gitOutput(clone, 'branch', '-f', BRANCH)
            gitOutput(clone, 'update-ref', 'refs/gnomish-factory/SALV-H', 'refs/heads/' + BRANCH)
            new LocalBoxEnvironment(clone, dir)
        }
        def before = gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH)
        if (committed) {
            new File(box.workingCopy.toFile(), 'work.txt').text = 'committed by the gnome'
            gitOutput(box.workingCopy, 'add', '-A')
            gitOutput(box.workingCopy, 'commit', '-q', '-m', 'gnome')
        }
        Files.writeString(box.workingCopy.resolve('.gnomish-task/state.json'), '{"stale":true}')

        expect: 'nothing was salvaged, nothing refused, nothing registered'
        !new EnvironmentSalvage(box, ClaimEpochSource.NONE, new GitProcessRunner(), cloneDir).salvageHarvested('SALV-H')
        gitOutput(cloneDir, 'rev-parse', 'refs/heads/' + BRANCH) == before
        gitOutput(cloneDir, 'rev-parse', 'refs/gnomish-factory/SALV-H') == before
        Files.readString(box.workingCopy.resolve('.gnomish-task/state.json')) == '{"recorded":true}'

        where:
        committed | gnome
        true | 'with a gnome commit above the register'
        false | 'with the box HEAD already on the branch'
    }

    def "FR13: a salvage the admission cannot judge fails the salvage, naming the admission"() {
        given: 'a clone whose register is gone'
        def box = box { Path clone, Path dir ->
            new LocalBoxEnvironment(clone, dir)
        }
        gitOutput(cloneDir, 'update-ref', '-d', 'refs/gnomish-factory/SALV-H')
        new File(box.workingCopy.toFile(), 'work.txt').text = 'interrupted round'

        when:
        new EnvironmentSalvage(box, ClaimEpochSource.NONE, new GitProcessRunner(), cloneDir).salvageHarvested('SALV-H')

        then:
        def ex = thrown(GitSalvageFailedException)
        ex.message.contains('harvest admission')
    }
}
