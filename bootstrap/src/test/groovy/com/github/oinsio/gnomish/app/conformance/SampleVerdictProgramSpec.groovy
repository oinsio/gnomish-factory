package com.github.oinsio.gnomish.app.conformance

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.app.describe.ProgramChannel
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.nio.file.Path

/**
 * FR8, M4 of migrate-verify-to-executor-contract: the api's sample module ships a verdict program —
 * a committed POSIX script, a shipped artefact rather than a test stand-in — and it passes the
 * conformance kit in role {@code verdict} with {@code poll}, run exactly as {@code gnomish executor
 * verify} runs a candidate. Its dependency-freedom is pinned here too: the program is the script
 * alone, and the only factory names it spells are the wire contract's variables.
 */
class SampleVerdictProgramSpec extends ConformanceKitProgramSpecBase implements BareGitRepoFixture {

    private static final String SAMPLE = 'gnomish-plugin-api/sample/verdict/sample-verdict.sh'

    private static Path sample() {
        RepoSourceTree.repoRoot().resolve(SAMPLE)
    }

    // Scenario: plugin/plugin-api-contract / Sample verdict program passes the kit
    // FR8, M4: every case passes or does not apply; the verdict and both pending cases pass, each
    //     poll from a working copy other than start's
    def "FR8: the sample verdict program passes every verdict and poll case of the kit"() {
        when:
        def report = verify(sample(), DEFER)

        then: 'no case failed, and the cases a verdict program with poll must pass did'
        failed(report) == []
        [
            KitCase.DESCRIBE,
            KitCase.SETTINGS_SCHEMA,
            KitCase.VERDICT,
            KitCase.PENDING_POLL,
            KitCase.PENDING_REPOLL,
            KitCase.UNKNOWN_ROLE,
            KitCase.STDERR_NOISE,
            KitCase.NON_JSON_STDOUT,
            KitCase.UNKNOWN_REQUEST_FIELDS
        ].every {
            report.verdicts()[it] instanceof CaseVerdict.Passed
        }

        and: 'every poll ran in the case poll copy, apart from the working copy start ran in'
        def polls = operations.findAll { it.op == 'poll' }
        polls.size() == 3
        polls.every { (it.dir as Path).parent == kitScratch.resolve('poll') }
    }

    // Scenario: plugin/plugin-api-contract / Sample verdict program passes the kit
    // FR8, M4 (task 6.1 hands the sample program's dependency-freedom to this feature): the program
    //     is one executable script with no build beside it, naming no jar, no JVM and no factory
    //     variable other than the contract's
    def "FR8, M4: the sample verdict program declares no factory dependency"() {
        given:
        def text = Files.readString(sample())
        def spelled = (text =~ /GNOMISH_[A-Z_]+/).collect { it } as Set

        expect: 'the program is the one executable file of its directory'
        Files.isExecutable(sample())
        Files.list(sample().parent).withCloseable { it.toList() } == [sample()]

        and: 'it names no build tool, jar or JVM'
        [
            '.jar',
            'java',
            'gradle',
            'mvn',
            'gnomish-plugin-api'
        ].every {
            !text.contains(it)
        }

        and: 'the only factory names it reads are the wire contract variables'
        spelled == [
            ProgramChannel.RESULT_FILE_VARIABLE,
            ProgramChannel.PENDING_KEY_VARIABLE
        ] as Set
    }

    // FR8 (the api delta's "A third party compiles against a single declared dependency": a verdict
    //     program reads the attempt commit as HEAD of the working copy its request names): a commit
    //     carrying DO-NOT-MERGE is an error result naming it, and a clean commit a note naming it
    def "FR8: the sample judges HEAD of the working copy its request names"() {
        given: 'a working copy whose HEAD #what'
        def copy = initWorkingRepo(tempDir, 'copy')
        commit(copy, file, 'x')
        def head = currentHead(copy)

        when:
        def results = start(copy).answer.sarif.runs[0].results

        then:
        results*.level == levels
        results*.message.text.every { it.contains(head) }

        where:
        what | file || levels
        'carries DO-NOT-MERGE' | 'DO-NOT-MERGE' || ['note', 'error']
        'carries no such file' | 'README' || ['note']
    }

    /** Runs the sample's {@code start} in role verdict over {@code copy}, and reads its result file. */
    private Map start(Path copy) {
        def request = tempDir.resolve('request.json')
        Files.writeString(request, '{"role":"verdict","workspace":{"path":"' + copy + '"},"settings":{}}')
        def resultFile = tempDir.resolve('result.json')
        def builder = new ProcessBuilder(sample().toString(), ProgramChannel.START)
        def environment = TestChildEnvironment.cleared(builder)
        environment[ProgramChannel.RESULT_FILE_VARIABLE] = resultFile.toString()
        def process = builder.redirectInput(request.toFile())
                .redirectErrorStream(true)
                .redirectOutput(tempDir.resolve('output.log').toFile())
                .start()
        assert process.waitFor() == 0
        new ObjectMapper().readValue(resultFile.toFile(), Map)
    }
}
