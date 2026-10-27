package com.github.oinsio.gnomish.app.doctor

import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.readings

import com.github.oinsio.gnomish.app.port.console.ConsoleIO
import com.github.oinsio.gnomish.app.project.ProjectResolution
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.config.OperatorLogFile
import java.nio.file.Path
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.core.env.Environment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

/**
 * A {@link DoctorCommand} over checks a spec writes inline (design D3 of add-doctor-command): the
 * survey finds no project, so a check that needs one is handed its context by the spec itself.
 */
final class DoctorCommandFixture {

    /** The log file {@link #logFileEnvironment()} publishes, as the loader would. */
    static final Path LOG_FILE = Path.of('/tmp/gnomish-home/logs/factory.log')

    private DoctorCommandFixture() {}

    /** An environment carrying {@code logFile} as the operator log file's owner publishes it. */
    static Environment logFileEnvironment(Path logFile = LOG_FILE) {
        def environment = new StandardEnvironment()
        environment.propertySources.addFirst(
                new MapPropertySource(OperatorLogFile.SOURCE, OperatorLogFile.properties(logFile)))
        environment
    }

    /** The command over {@code checks}, printing to {@code console}, outside any project. */
    static DoctorCommand command(List<DoctorCheck> checks, ConsoleIO console) {
        def beans = new DefaultListableBeanFactory()
        beans.registerSingleton('value', new ProjectResolution.Unregistered(Path.of('/tmp/x'), false, 'r'))
        def survey = new DoctorSurvey(beans.getBeanProvider(ProjectResolution), readings())
        new DoctorCommand(checks, survey, new DefaultListableBeanFactory().getBeanProvider(RegisteredClone), console,
                logFileEnvironment())
    }

    /** A check named {@code id} whose results are {@code body}'s, called with the run's context. */
    static DoctorCheck check(DoctorCheckId id, Closure<List<DoctorVerdict>> body) {
        new DoctorCheck() {
                    @Override
                    DoctorCheckId id() {
                        id
                    }

                    @Override
                    List<DoctorVerdict> run(DoctorContext context) {
                        body.call(context)
                    }
                }
    }
}
