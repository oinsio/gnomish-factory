package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * Where the in-process git fake stops (FR27, M13 of supervise-daemon-loops-and-embed-dashboard,
 * design D27): {@code ScriptedGit} stands in for the runner's role above the process boundary, and
 * nowhere at it. A class that builds its own {@code ProcessBuilder} is specified against the real
 * process — a stand-in preset or the real binary — and so is an end-to-end suite; a spec of either
 * that reached for the fake would no longer exercise the process it exists to pin.
 */
class ProcessOwnerStandInSpec extends Specification {

    static final String FAKE = 'ScriptedGit'

    // Scenario: build/quality-gates / A process-owning class keeps its stand-in
    def "FR27: the specs of every ProcessBuilder owner drive the owner itself, and no end-to-end suite uses the fake"() {
        given: 'the production classes that construct a process, comments stripped'
        def owners = RepoSourceTree.productionSources { it.endsWith('.java') }
        .findAll {
            RepoSourceTree.code(it).contains('new ProcessBuilder(')
        }
        .collect { it.name - '.java' }
        def tests = RepoSourceTree.testSources { it.endsWith('Spec.groovy') }
        def specsOf = { String owner ->
            tests.findAll {
                it.name ==~ /${owner}(\w*)Spec\.groovy/
            }
        }

        expect: 'the scan found the git, git-objects and docker owners, each with specs'
        owners.containsAll([
            'GitProcessRunner',
            'GitExec',
            'DockerCli'
        ])
        owners.every { !specsOf(it).isEmpty() }

        and: 'every spec of an owner calls the owner itself, and never the fake'
        owners.collectMany { owner ->
            specsOf(owner).findAll { spec ->
                def code = RepoSourceTree.code(spec)
                code.contains(FAKE) || !(code.contains("new ${owner}(") || code.contains("${owner}."))
            }.collect { RepoSourceTree.relative(it) }
        } == []

        and: 'no end-to-end suite uses the fake either'
        def e2e = tests.findAll { it.name.endsWith('E2ESpec.groovy') }
        e2e.size() > 5
        e2e.findAll {
            RepoSourceTree.code(it).contains(FAKE)
        }.collect {
            RepoSourceTree.relative(it)
        } == []
    }
}
