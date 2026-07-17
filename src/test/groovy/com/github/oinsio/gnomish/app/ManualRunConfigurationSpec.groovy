package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryApplication
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.console.SystemConsoleIO
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.adapter.engine.SystemClock
import com.github.oinsio.gnomish.adapter.engine.ThreadSleeper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import spock.lang.Specification

/**
 * D10 of add-manual-run: {@link ManualRunConfiguration} assembles every {@code gnomish run}
 * collaborator that needs no per-invocation data as a Spring bean — the context-independent
 * half of the {@code EnginePorts} bean graph. The full {@link FactoryApplication} context is
 * booted (mirroring FactoryApplicationSpec) since these beans are component-scanned under it;
 * this also proves the configuration coexists with the untouched no-args bootstrap (task 7.12).
 */
@SpringBootTest(classes = FactoryApplication)
class ManualRunConfigurationSpec extends Specification {

    @Autowired
    ApplicationContext context

    @Autowired
    FilesExistCheckRunner filesExistCheckRunner

    @Autowired
    ShellCommandCheckRunner shellCommandCheckRunner

    @Autowired
    InMemoryAttemptPersistence attemptPersistence

    @Autowired
    SystemClock systemClock

    @Autowired
    ThreadSleeper threadSleeper

    @Autowired
    SystemConsoleIO systemConsoleIO

    @Autowired
    RunArgumentsParser runArgumentsParser

    @Autowired
    PipelineStartup pipelineStartup

    @Autowired
    RunExitCodeMapper runExitCodeMapper

    @Autowired
    AdHocTaskSynthesizer adHocTaskSynthesizer

    @Autowired
    ManualRunRunner manualRunRunner

    def "the context boots with every context-independent EnginePorts collaborator wired"() {
        expect:
        context != null
        filesExistCheckRunner != null
        shellCommandCheckRunner != null
        attemptPersistence != null
        systemClock != null
        threadSleeper != null
        systemConsoleIO != null
    }

    def "the runner-level components (task 7.1-7.9) are present in the same context"() {
        expect:
        runArgumentsParser != null
        pipelineStartup != null
        runExitCodeMapper != null
        adHocTaskSynthesizer != null
    }

    def "the ApplicationRunner entrypoint is registered exactly once"() {
        expect:
        manualRunRunner != null
        context.getBeansOfType(org.springframework.boot.ApplicationRunner).size() == 1
    }

    def "context-independent beans are singletons: repeated lookups return the same instance"() {
        expect:
        context.getBean(FilesExistCheckRunner).is(filesExistCheckRunner)
        context.getBean(InMemoryAttemptPersistence).is(attemptPersistence)
    }
}
