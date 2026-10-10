package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * FR24, M11 of supervise-daemon-loops-and-embed-dashboard (design D23, ADR 0015): a test never
 * writes an executable file. A stand-in binary is a committed preset of the library under
 * {@code test-fixtures/src/main/resources/stand-in/}, handed out by its one owner, {@code StandIn},
 * which creates nothing but symbolic links to it.
 *
 * <p>The failure this gate exists for had a green build: 61 shell scripts in about fifty specs, each
 * written fresh per test, so macOS assessed every one on its first run (1–4 s, queued across PIT's
 * minions) and PIT spent most of its minion time waiting on scripts — invisible on Linux and to the
 * count-based cost report.
 *
 * <p>{@link StandInRule} decides what writing one looks like. The scan covers every test tree at
 * any depth and {@code :test-fixtures}' own sources, comments stripped. The exemptions are paths,
 * each with its reason, and the scan must still find a shape in each, so an exemption that stopped
 * offending fails loudly rather than staying behind as a silent widening.
 */
class StandInOwnerSpec extends Specification {

    private static final String BOOT = 'bootstrap/src/test/groovy/com/github/oinsio/gnomish/'

    /** Files allowed to spell a stand-in binary, each with why. */
    private static final Map<String, String> EXEMPT = [
        ('adapters/git/src/test/groovy/com/github/oinsio/gnomish/sandbox/environment/ContainerGitMechanicsSpec.groovy'):
        'a hook planted inside a running container, where Linux runs it and a host link would not resolve',
        (BOOT + 'app/FakeAgentSandboxImage.groovy'):
        'the fake agent installed into a sandbox image by its Dockerfile, run by Linux inside the box',
        (BOOT + 'distribution/LauncherScriptSpec.groovy'):
        'a spec of the shipped launcher script, with a stub java on its PATH; it runs once per build',
        (BOOT + 'distribution/ReleasePreflightScriptSpec.groovy'):
        'a spec of the shipped release preflight script, with a stub gh on its PATH; it runs once per build',
        (BOOT + 'architecture/NightlyMutationIssueScriptSpec.groovy'):
        'a spec of the shipped nightly mutation script, with a stub gh on its PATH; it runs once per build',
        (BOOT + 'architecture/StallingGitOwnerSpec.groovy'):
        'a gate\'s own seeded sources: shebang text as data for its detector, never written to disk',
    ]

    /** This gate's own sources spell every shape it scans for. */
    private static final List<String> THIS_GATE = [
        BOOT + 'architecture/StandInOwnerSpec.groovy',
        BOOT + 'architecture/StandInRule.groovy',
    ]

    /** The owner: the one source that creates a stand-in on disk, and only as a link. */
    private static final String OWNER =
    'test-fixtures/src/main/groovy/com/github/oinsio/gnomish/testfixtures/standin/StandIn.groovy'

    // FR24, M11: no test source or fixture writes a stand-in binary outside the exemptions
    def "FR24, M11: the files that spell a stand-in binary are exactly the exemptions"() {
        given: 'every test source at any depth and every :test-fixtures source, comments stripped'
        def sources = RepoSourceTree.testSources() + RepoSourceTree.productionSources {
            it.startsWith('test-fixtures/src/main/')
        }
        def scanned = sources.findAll {
            !THIS_GATE.contains(RepoSourceTree.relative(it))
        }
        def offending = scanned.collectEntries { file ->
            [(RepoSourceTree.relative(file)): StandInRule.shapesIn(RepoSourceTree.code(file))]
        }.findAll { it.value }

        expect: 'the scan really reached the trees, the owner among them'
        scanned.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        scanned.collect { RepoSourceTree.relative(it) }.contains(OWNER)

        and: 'the owner creates links, never an executable file'
        !offending.containsKey(OWNER)

        and: 'the files that spell one are exactly the exemptions — each still does, so none is stale'
        offending.keySet().sort() == EXEMPT.keySet().sort()
    }

    // The detector is the gate — each shape must be found, and the look-alikes must not
    def "the detector: #what"() {
        expect:
        StandInRule.shapesIn(code) == shapes

        where:
        what | code || shapes
        'an executable property' | 'script.toFile().executable = true' || ['executable bit']
        'a setExecutable call' | 'hook.setExecutable(true)' || ['executable bit']
        'a shebang in a literal' | "def s = '#!/bin/sh\\nexit 1\\n'" || ['shebang']
        'an env shebang' | 'def s = """#!/usr/bin/env bash\necho"""' || ['shebang']
        'a chmod through a shell' | 'run("chmod +x hook")' || ['chmod granting execute']
        'a chmod for all, read and execute' | 'RUN chmod a+rx /opt/fake' || ['chmod granting execute']
        'a recursive chmod' | 'RUN chmod -R a+x /opt/fake' || ['chmod granting execute']
        'a link to a committed preset' | "StandIn.link(hooks.resolve('pre-commit'), 'hook-refuse')" || []
        'a directory locked by mode' | "Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString('rwx------'))" || []
        'a chmod that removes execute' | 'run("chmod -x hook")' || []
        'a read of the executable bit' | 'assert Files.isExecutable(script)' || []
    }
}
