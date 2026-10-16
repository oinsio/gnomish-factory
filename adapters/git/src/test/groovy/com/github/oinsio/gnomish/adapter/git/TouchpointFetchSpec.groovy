package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.gittransfer.Refspec
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR13 of tighten-spec-contract (design D13): the touchpoint fetch moves the task branch and the
 * factory register in one transfer, and falls back to the branch alone only when origin carries
 * no register for the task — any other failure is the caller's, after exactly one fetch.
 */
class TouchpointFetchSpec extends Specification {

    private static final Path CLONE = Path.of('/clone')
    private static final Refspec BRANCH = new Refspec('gnomish/T-1:refs/remotes/origin/gnomish/T-1')
    private static final Refspec REGISTER = FactoryRegisterRefs.fetchedInPlace('T-1')

    /** The refspecs one fetch named: what follows the remote. */
    private static List<String> refspecsOf(ScriptedGit.Call call) {
        def argv = call.command()
        argv.drop(argv.indexOf('--end-of-options') + 2)
    }

    def "FR13: one transfer names the branch and the register, and its result is the caller's"() {
        given:
        def git = ScriptedGit.reftableClone().answer(['fetch'], exit, '', stderr)

        when:
        def result = new TouchpointFetch(git).fetch(CLONE, 'T-1', BRANCH, REGISTER)

        then:
        git.calls(['fetch']).size() == 1
        refspecsOf(git.calls(['fetch'])[0]) == [
            BRANCH.value(),
            '+refs/gnomish-factory/T-1:refs/gnomish-factory/T-1'
        ]
        result.exitCode() == exit

        where:
        exit | stderr
        0 | ''
        128 | 'fatal: unable to access origin\n'
        128 | "fatal: couldn't find remote ref refs/heads/gnomish/T-1\n"
    }

    def "FR13: a branch origin holds no register for is fetched alone, by a second transfer"() {
        given:
        def git = ScriptedGit.reftableClone()
                .answers(['fetch'],
                GitCommandResult.of(128, '', "fatal: couldn't find remote ref refs/gnomish-factory/T-1\n"),
                GitCommandResult.of(0, '', ''))

        when:
        def result = new TouchpointFetch(git).fetch(CLONE, 'T-1', BRANCH, REGISTER)

        then:
        git.calls(['fetch']).size() == 2
        refspecsOf(git.calls(['fetch'])[1]) == [BRANCH.value()]
        result.exitCode() == 0
    }
}
