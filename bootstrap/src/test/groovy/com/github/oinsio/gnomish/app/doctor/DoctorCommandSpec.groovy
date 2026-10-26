package com.github.oinsio.gnomish.app.doctor

import static com.github.oinsio.gnomish.app.doctor.DoctorSpecSupport.readings

import com.github.oinsio.gnomish.app.port.console.ConsoleIO
import com.github.oinsio.gnomish.app.project.ProjectResolution
import com.github.oinsio.gnomish.app.project.RegisteredClone
import java.nio.file.Path
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification

/**
 * FR1, FR7 of add-doctor-command (design D3): the command runs every check in the order of {@link
 * DoctorCheckId}, whatever order the beans arrive in, over one context; a check that throws is one
 * failed result and the next check still runs; each result is printed with its fix below it.
 */
class DoctorCommandSpec extends Specification {

    ConsoleIO console = Mock()

    def "FR1: every check runs in report order, and a throwing check is one failure that stops nothing"() {
        given: 'three checks handed in reverse order, the middle one throwing'
        def legacy = check(DoctorCheckId.LEGACY_LAYOUT) {
            [
                new DoctorVerdict(DoctorCheckId.LEGACY_LAYOUT, DoctorStatus.OK, 'clean', null)
            ]
        }
        def pipeline = check(DoctorCheckId.PIPELINE) {
            throw new IllegalStateException('boom')
        }
        def git = check(DoctorCheckId.GIT_FLOOR) {
            [
                new DoctorVerdict(DoctorCheckId.GIT_FLOOR, DoctorStatus.FAIL, 'old', 'upgrade')
            ]
        }
        def command = command([legacy, pipeline, git])

        expect:
        command.verdicts() == [
            new DoctorVerdict(DoctorCheckId.GIT_FLOOR, DoctorStatus.FAIL, 'old', 'upgrade'),
            new DoctorVerdict(DoctorCheckId.PIPELINE, DoctorStatus.FAIL, 'the check could not run: boom', null),
            new DoctorVerdict(DoctorCheckId.LEGACY_LAYOUT, DoctorStatus.OK, 'clean', null),
        ]
    }

    def "FR1: each check is handed the one context the survey read"() {
        given:
        def seen = []
        def first = check(DoctorCheckId.FACTORY_VERSION) { DoctorContext c ->
            seen << c; []
        }
        def second = check(DoctorCheckId.HOOKS_PATH) { DoctorContext c ->
            seen << c; []
        }

        when:
        command([second, first]).verdicts()

        then:
        seen.size() == 2
        seen[0].is(seen[1])
        seen[0].project() instanceof ProjectResolution.Unregistered
    }

    def "FR5: a result is its status, name and detail, with the fix on the line below when there is one"() {
        given:
        def command = command([
            check(DoctorCheckId.GUARD_IMAGE) {
                [
                    new DoctorVerdict(DoctorCheckId.GUARD_IMAGE, DoctorStatus.WARN, 'absent', 'docker pull g'),
                    new DoctorVerdict(DoctorCheckId.GUARD_IMAGE, DoctorStatus.SKIPPED, 'no box', null),
                ]
            }
        ])

        when:
        command.run(new DefaultApplicationArguments('doctor'))

        then:
        1 * console.print('warn  guard image: absent' + ConsoleIO.LINE_END + '      fix: docker pull g' + ConsoleIO.LINE_END)

        then:
        1 * console.print('skipped  guard image: no box' + ConsoleIO.LINE_END)
        0 * console._
    }

    private DoctorCommand command(List<DoctorCheck> checks) {
        def resolution = new ProjectResolution.Unregistered(Path.of('/tmp/x'), false, 'r')
        def survey = new DoctorSurvey(provider(ProjectResolution, resolution), readings())
        new DoctorCommand(checks, survey, new DefaultListableBeanFactory().getBeanProvider(RegisteredClone), console)
    }

    private static DoctorCheck check(DoctorCheckId id, Closure<List<DoctorVerdict>> body) {
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

    private static <T> ObjectProvider<T> provider(Class<T> type, T value) {
        def beans = new DefaultListableBeanFactory()
        beans.registerSingleton('value', value)
        beans.getBeanProvider(type)
    }
}
