package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.sandbox.environment.DockerUnavailableException
import com.github.oinsio.gnomish.subprocess.Termination
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR5 of add-sandbox-core: the factory-side harvest fetch — a factory-fixed
 * {@code ext::docker exec} transport URL and refspec (never values from the
 * box), landing in the task's quarantine and never on the task branch (design
 * D18 of tighten-spec-contract — a rewritten history is the admission's refusal,
 * {@code HarvestAdmissionSpec}), and the failure classification (validation
 * refusal / daemon outage / plain failure). The argv is the transfer owner's for a container source (FR4, FR6
 * of own-git-transfer-argv), pinned here as the exact list so a flag that
 * leaves the owner's common set is seen at this consumer too. The real
 * box-to-factory transport is exercised by the Docker-gated specs; here the
 * runner's port is scripted in process ({@link ScriptedGit}, design D27 of
 * supervise-daemon-loops-and-embed-dashboard), which pins the exact argv the
 * fetch hands the runner and the classification of each answer without a daemon.
 */
class ContainerHarvestFetchSpec extends Specification {

    @TempDir
    Path tempDir

    // The runner prefixes its own stall-detection options onto a fetch (FR4 of
    // bound-subprocess-commands), pinned by the runner's own specs; what this spec pins is the
    // caller's half of the argv, exactly as the fetch hands it to the runner.
    // Scenario: git/git-task-persistence / The harvest admits only the container transport
    // Scenario: git/git-task-persistence / The branch moves only through the admission
    def "FR5, FR4, FR6: fetch runs the owner's container argv — common set, ext transport, the quarantine refspec"() {
        given: 'a git whose fetch succeeds'
        def git = new ScriptedGit().answer(['fetch'], 0)

        when:
        new ContainerHarvestFetch(git, tempDir).fetch('gnomish-box-k7', 'gnomish/task-1')

        then: 'the owner\'s exact list: no protocol.ext.allow — the allowlist alone enables ext'
        git.calls()*.argv == [
            [
                '-c',
                'fetch.recurseSubmodules=no',
                '-c',
                'submodule.recurse=false',
                '-c',
                'fetch.prune=false',
                '-c',
                'maintenance.auto=false',
                '-c',
                'gc.auto=0',
                '-c',
                'fetch.fsckObjects=true',
                '-c',
                'transfer.fsckObjects=true',
                '-c',
                'fetch.fsck.badTimezone=ignore',
                '-c',
                'fetch.fsck.missingSpaceBeforeDate=ignore',
                '-c',
                'fetch.fsck.zeroPaddedFilemode=ignore',
                'fetch',
                '--no-tags',
                '--no-recurse-submodules',
                '--no-write-fetch-head',
                '--end-of-options',
                'ext::docker exec -i gnomish-box-k7 %S /gnomish/work',
                '+refs/heads/gnomish/task-1:refs/gnomish-quarantine/task-1',
            ]
        ]
    }

    /** What git prints for each refusal the fetch classifies, as git prints it. */
    private static final String DAEMON_DOWN = 'docker: Cannot connect to the Docker daemon at unix:///var/run/docker.sock\n'
    private static final String NOT_A_REPOSITORY = "fatal: '/gnomish/work' does not appear to be a git repository\n"

    /** A git whose one fetch fails with {@code stderr}. */
    private static ScriptedGit refusing(String stderr) {
        new ScriptedGit().answer(['fetch'], 128, '', stderr)
    }

    // FR5, NFR-R1, UX3 of own-git-transfer-argv (design D4): asked before the daemon read, and
    //     mapped to a boundary violation, with the report naming the message id and the object.
    // Scenario: git/git-task-persistence / A malformed object is refused with the rewrite's report shape
    // Scenario: git/git-transfer / A malformed .gitmodules from the box is refused
    def "FR5: an object refused by validation is a boundary violation naming the object, never a plain failure"() {
        given:
        def git = refusing(FetchRefusalSpec.FETCH_REFUSAL)

        when:
        new ContainerHarvestFetch(git, tempDir).fetch('box', 'gnomish/task-1')

        then:
        def ex = thrown(HarvestRefusedException)
        ex.message.contains('harvest refused for branch "gnomish/task-1"')
        ex.message.contains('failed validation')
        ex.message.contains('missingEmail')
        ex.message.contains(FetchRefusalSpec.OBJECT)
        !ex.message.contains('history was rewritten')
    }

    def "NFR-R1: a daemon outage during harvest classifies as infrastructure, never a harvest failure"() {
        given:
        def git = refusing(DAEMON_DOWN)

        when:
        new ContainerHarvestFetch(git, tempDir).fetch('box', 'gnomish/task-1')

        then:
        thrown(DockerUnavailableException)
    }

    def "FR5: any other fetch failure is a plain harvest failure carrying git's stderr"() {
        given:
        def git = refusing(NOT_A_REPOSITORY)

        when:
        new ContainerHarvestFetch(git, tempDir).fetch('box', 'gnomish/task-1')

        then:
        def ex = thrown(HarvestFailedException)
        ex.message.contains('does not appear to be a git repository')
    }

    def "FR5: a clean fetch throws nothing"() {
        given:
        def git = new ScriptedGit().answer(['fetch'], 0)

        when:
        new ContainerHarvestFetch(git, tempDir).fetch('box', 'gnomish/task-1')

        then:
        noExceptionThrown()
    }

    // FR7 of bound-subprocess-commands: a fetch killed on its deadline printed at most a partial
    // transcript, and git writes its validation refusal at the very end — so the transcript must
    // not be classified at all. It is a plain harvest failure, named as unfinished.
    def "FR7: a fetch cut off on its deadline is an unfinished harvest, not a validation refusal"() {
        given: 'a fetch the runner cut off on its deadline, its partial transcript ending in a validation refusal'
        def git = new ScriptedGit().answer(['fetch'], GitCommandResult.of(143, '', FetchRefusalSpec.FETCH_REFUSAL, Termination.TIMED_OUT))

        when:
        new ContainerHarvestFetch(git, tempDir).fetch('box', 'gnomish/task-1')

        then:
        def ex = thrown(HarvestFailedException)
        ex.message.contains('was cut off on its deadline')
        !ex.message.contains('failed validation')
    }
}
