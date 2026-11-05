package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link ProgramExecutorFactory} (design D2 of add-command-executor; FR10 of define-executor-contract):
 * the program factory of the shared round wrapper builds exactly what the wrapper built itself — the
 * declaration's name, the request {@link ProgramRequests} builds from the frozen law, and a {@link
 * ProgramExecutor} answering the describe the preflight obtained.
 */
class ProgramExecutorFactorySpec extends Specification {

    @TempDir
    Path stderrParent

    private ProgramExecutorFactory factory() {
        new ProgramExecutorFactory(PipelineLaw.ofContent(['instructions.md': 'build it\n']),
        new DescribedProgram(ProgramFixtures.declaration(['unused']), ProgramFixtures.describe(), ['ACME_API_KEY']),
        ProgramFixtures.equipment(stderrParent))
    }

    // FR8 of add-command-executor (D2): the entry's name is the law declaration's
    def "FR8: the program factory names the executor by its law declaration"() {
        expect:
        factory().name() == ProgramFixtures.NAME
    }

    // FR8 of add-command-executor (D2): the round's request is the program request over the frozen law
    def "FR8: the program factory's request is the one the program request builder gives"() {
        expect:
        factory().request(ProgramFixtures.round(Path.of('.'))) == ProgramFixtures.request()
    }

    // FR8 of add-command-executor (D2): the round's executor is a program executor with the preflight's describe
    def "FR8: the program factory builds a program executor answering the preflight's describe"() {
        when:
        def executor = factory().executor(Stub(TaskExecutionEnvironment))

        then:
        executor instanceof ProgramExecutor
        executor.describe() == ProgramFixtures.describe()
    }
}
