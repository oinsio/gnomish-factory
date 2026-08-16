package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.sandbox.environment.DockerUnavailableException
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR5 of add-sandbox-core: the factory-side harvest fetch — a factory-fixed
 * {@code ext::docker exec} transport URL and refspec (never values from the
 * box), fast-forward-only by the absence of {@code +}, and the three-way
 * failure classification (rewrite refusal / daemon outage / plain failure).
 * The real box-to-factory transport is exercised by the Docker-gated specs;
 * here a recording fake git binary pins the exact argv without a daemon.
 */
class ContainerHarvestFetchSpec extends Specification {

    @TempDir
    Path tempDir

    def "FR5: fetch runs the factory-fixed argv — no-recurse-submodules, ext transport, unforced refspec"() {
        given: 'a fake git binary that records its argv'
        def record = tempDir.resolve('args.txt')
        def git = fakeGit(0, '', record)

        when:
        new ContainerHarvestFetch(new GitProcessRunner(git.toString()), tempDir)
                .fetch('gnomish-box-k7', 'gnomish/task-1')

        then:
        Files.readAllLines(record) == [
            '-c',
            'protocol.ext.allow=user',
            'fetch',
            '--no-recurse-submodules',
            'ext::docker exec -i gnomish-box-k7 %S /gnomish/work',
            'gnomish/task-1:gnomish/task-1',
        ]
    }

    def "FR5: a non-fast-forward refusal surfaces as the history-rewrite violation"() {
        given:
        def git = fakeGit(1, '! [rejected] gnomish/task-1 -> gnomish/task-1 (non-fast-forward)')

        when:
        new ContainerHarvestFetch(new GitProcessRunner(git.toString()), tempDir).fetch('box', 'gnomish/task-1')

        then:
        def ex = thrown(HarvestRefusedException)
        ex.message.contains('gnomish/task-1')
        ex.message.contains('history was rewritten')
    }

    def "NFR-R1: a daemon outage during harvest classifies as infrastructure, never a harvest failure"() {
        given:
        def git = fakeGit(128, 'docker: Cannot connect to the Docker daemon at unix:///var/run/docker.sock')

        when:
        new ContainerHarvestFetch(new GitProcessRunner(git.toString()), tempDir).fetch('box', 'gnomish/task-1')

        then:
        thrown(DockerUnavailableException)
    }

    def "FR5: any other fetch failure is a plain harvest failure carrying git's stderr"() {
        given:
        def git = fakeGit(128, "fatal: '/gnomish/work' does not appear to be a git repository")

        when:
        new ContainerHarvestFetch(new GitProcessRunner(git.toString()), tempDir).fetch('box', 'gnomish/task-1')

        then:
        def ex = thrown(HarvestFailedException)
        ex.message.contains('does not appear to be a git repository')
    }

    def "FR5: a clean fetch throws nothing"() {
        given:
        def git = fakeGit(0, '')

        when:
        new ContainerHarvestFetch(new GitProcessRunner(git.toString()), tempDir).fetch('box', 'gnomish/task-1')

        then:
        noExceptionThrown()
    }

    private int scriptCounter = 0

    private Path fakeGit(int exitCode, String stderr, Path record = null) {
        def script = tempDir.resolve("fake-git-${scriptCounter++}.sh")
        def lines = ['#!/bin/sh']
        if (record != null) {
            lines.add("printf '%s\\n' \"\$@\" > '${record}'".toString())
        }
        if (stderr) {
            lines.add("echo '${stderr.replace("'", '')}' 1>&2".toString())
        }
        lines.add("exit ${exitCode}".toString())
        script.toFile().text = lines.join('\n') + '\n'
        script.toFile().setExecutable(true)
        script
    }
}
