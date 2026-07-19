package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.console.ConsoleClosedException
import com.github.oinsio.gnomish.adapter.git.DivergedBranchException
import spock.lang.Specification

/**
 * FR9, FR12, D10 of add-git-workflow, add-manual-run: every terminal exception type maps to
 * its documented exit code, including a fallback of 1 for anything unrecognized.
 */
class RunExitCodeMapperSpec extends Specification {

    private RunExitCodeMapper mapper = new RunExitCodeMapper()

    def "getExitCode maps #exception.class.simpleName to #expectedCode"() {
        expect:
        mapper.getExitCode(exception) == expectedCode

        where:
        exception                                                                  | expectedCode
        new UsageException('bad flag')                                            | 2
        new PipelineLoadFailedException(['error line'])                           | 3
        new InputExhaustedException()                                             | 4
        new DivergedBranchException('PROJ-1', 'gnomish/PROJ-1', 'aaa', 'bbb')      | 5
        new TaskNotFoundException('PROJ-1')                                       | 6
        new EscalationEofException(new ConsoleClosedException())                  | 10
        new CheckpointEofException(new ConsoleClosedException())                  | 11
        new AbortedException('persist failed')                                    | 12
        new InternalErrorException('mismatch')                                    | 1
    }

    def "getExitCode falls back to 1 for an unrecognized Throwable"() {
        expect:
        mapper.getExitCode(new RuntimeException('unexpected')) == 1
        mapper.getExitCode(new IllegalStateException('surprise')) == 1
    }
}
