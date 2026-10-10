package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.sandbox.environment.DockerUnavailableException
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.testfixtures.standin.StandInLog
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR5 of add-sandbox-core: the factory-side harvest fetch — a factory-fixed
 * {@code ext::docker exec} transport URL and refspec (never values from the
 * box), fast-forward-only by the absence of {@code +}, and the failure
 * classification (validation refusal / rewrite refusal / daemon outage / plain
 * failure). The argv is the transfer owner's for a container source (FR4, FR6
 * of own-git-transfer-argv), pinned here as the exact list so a flag that
 * leaves the owner's common set is seen at this consumer too. The real
 * box-to-factory transport is exercised by the Docker-gated specs; here a
 * recording fake git binary pins the exact argv without a daemon.
 */
class ContainerHarvestFetchSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    // The runner prefixes its own stall-detection options onto a fetch (FR4 of
    // bound-subprocess-commands); what this spec pins is the caller's half of the argv, which
    // follows them.
    def "FR5, FR4, FR6: fetch runs the owner's container argv — common set, ext transport, unforced refspec"() {
        given: 'a fake git binary that records its argv'
        def git = StandIn.recording(tempDir, 'record-argv')

        when:
        new ContainerHarvestFetch(new GitProcessRunner(git.toString()), tempDir)
                .fetch('gnomish-box-k7', 'gnomish/task-1')

        then: 'the owner\'s exact list: no protocol.ext.allow — the allowlist alone enables ext'
        StandInLog.argv(StandInLog.blocks(git).last()) == stallDetectionArgv() + [
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
            'gnomish/task-1:gnomish/task-1',
        ]
    }

    // FR5, NFR-R1, UX3 of own-git-transfer-argv (design D4): asked before the daemon and
    //     non-fast-forward reads, and mapped to the same boundary violation as a rewrite, with the
    //     report naming the message id and the object.
    def "FR5: an object refused by validation is a boundary violation naming the object, never a plain failure"() {
        given:
        def git = StandIn.git('harvest-fsck-refused')

        when:
        new ContainerHarvestFetch(new GitProcessRunner(git.toString()), tempDir).fetch('box', 'gnomish/task-1')

        then:
        def ex = thrown(HarvestRefusedException)
        ex.message.contains('harvest refused for branch "gnomish/task-1"')
        ex.message.contains('failed validation')
        ex.message.contains('missingEmail')
        ex.message.contains(FetchRefusalSpec.OBJECT)
        !ex.message.contains('history was rewritten')
    }

    def "FR5: a non-fast-forward refusal surfaces as the history-rewrite violation"() {
        given:
        def git = StandIn.git('harvest-rejected')

        when:
        new ContainerHarvestFetch(new GitProcessRunner(git.toString()), tempDir).fetch('box', 'gnomish/task-1')

        then:
        def ex = thrown(HarvestRefusedException)
        ex.message.contains('gnomish/task-1')
        ex.message.contains('history was rewritten')
    }

    def "NFR-R1: a daemon outage during harvest classifies as infrastructure, never a harvest failure"() {
        given:
        def git = StandIn.git('harvest-daemon-down')

        when:
        new ContainerHarvestFetch(new GitProcessRunner(git.toString()), tempDir).fetch('box', 'gnomish/task-1')

        then:
        thrown(DockerUnavailableException)
    }

    def "FR5: any other fetch failure is a plain harvest failure carrying git's stderr"() {
        given:
        def git = StandIn.git('harvest-not-a-repository')

        when:
        new ContainerHarvestFetch(new GitProcessRunner(git.toString()), tempDir).fetch('box', 'gnomish/task-1')

        then:
        def ex = thrown(HarvestFailedException)
        ex.message.contains('does not appear to be a git repository')
    }

    def "FR5: a clean fetch throws nothing"() {
        given:
        def git = StandIn.recording(tempDir, 'record-argv')

        when:
        new ContainerHarvestFetch(new GitProcessRunner(git.toString()), tempDir).fetch('box', 'gnomish/task-1')

        then:
        noExceptionThrown()
    }

    // FR7 of bound-subprocess-commands: a fetch killed on its deadline printed at most a partial
    // transcript, and git writes its non-fast-forward refusal at the very end — so the transcript
    // must not be classified at all. It is a plain harvest failure, named as unfinished.
    def "FR7: a fetch cut off on its deadline is an unfinished harvest, not a rewrite refusal"() {
        given: 'a git that never returns, against a deadline far shorter than its stall'
        def git = stallingGit()

        when:
        new ContainerHarvestFetch(new GitProcessRunner(git.toString(), Duration.ofSeconds(2)), tempDir)
                .fetch('box', 'gnomish/task-1')

        then:
        def ex = thrown(HarvestFailedException)
        ex.message.contains('was cut off on its deadline')
        !ex.message.contains('history was rewritten')
    }

    // Long enough that the 2s deadline is the only thing that can end this fetch, short enough
    // that a mutant which drops the bound fails on the stand-in's own exit instead of hanging. Only
    // the fetch stalls: the runner's clone-key resolution in front of it answers at once.
    private Path stallingGit() {
        StallingGit.git('stall-fetch')
    }
}
