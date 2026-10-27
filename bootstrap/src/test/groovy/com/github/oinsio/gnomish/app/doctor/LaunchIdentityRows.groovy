package com.github.oinsio.gnomish.app.doctor

import static com.github.oinsio.gnomish.app.doctor.IdentityRow.refusalOf

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.DoctorProjectReadings.StageBindings
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.project.ProjectResolution
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The identity rows of the host-bound checks (M1 of add-doctor-command): the agent CLI binary and
 * the process-group launcher helper. The startup side is the host launch owner's launching form,
 * {@code ProcessLaunch.grouped().start}, which an agent round calls with {@code
 * factory.agent-cli-binary}, the clone as its directory and the {@code PATH} the round composes; the
 * doctor side is the real check over the launch owner's own assessments, with the same three.
 */
final class LaunchIdentityRows {

    /** The {@code PATH} the round's secrets port hands the child. */
    private static final String ROUND_PATH = '/usr/bin:/bin'

    private LaunchIdentityRows() {}

    static List<IdentityRow> all() {
        [
            new IdentityRow(DoctorCheckId.AGENT_CLI, { Path dir ->
                [
                    agentCli(dir, StandIn.process('quick').toString(), 'a committed stand-in CLI'),
                    agentCli(dir, 'gnomish-identity-absent-cli', 'a name on no PATH entry'),
                ]
            }),
            // The helper is this host's own — the platform's launcher and /bin/kill — and :bootstrap
            // cannot take it away, so the row judges the host as it is: the launch starts exactly
            // when doctor reports the helper present. The missing-helper half of the identity is
            // pinned at the owner, LaunchAssessmentSpec in :subprocess, over seven platform rows.
            new IdentityRow(DoctorCheckId.LAUNCHER_HELPER, { Path dir ->
                [launcherHelper(dir)]
            }),
        ]
    }

    private static IdentityRow.Case agentCli(Path dir, String binary, String label) {
        def run = launched(dir, binary)
        def doctor = new AgentCliDoctorCheck(new ProcessLaunchAssessment(), run.readings, new FactoryProperties(null, binary, null, null))
                .run(run.context)
        IdentityRow.Case.refusing(label, run.refusal, doctor, run.refusal == null ? [] : [run.refusal.message])
    }

    private static IdentityRow.Case launcherHelper(Path dir) {
        def run = launched(dir, StandIn.process('quick').toString())
        def doctor = new LauncherHelperDoctorCheck(new ProcessLaunchAssessment()).run(run.context)
        IdentityRow.Case.refusing('this host', run.refusal, doctor, run.refusal == null ? [] : [run.refusal.message])
    }

    /** One launch of {@code binary} as an agent round makes it, and the doctor context over the same clone and law. */
    private static Map launched(Path dir, String binary) {
        Path clone = Files.createDirectories(dir.resolve('clone'))
        def readings = DoctorSpecSupport.readings([PATH: ROUND_PATH])
        def definition = DoctorSpecSupport.definition()
        String path = readings.childPath(definition)
        assert path == ROUND_PATH
        def builder = new ProcessBuilder(binary).directory(clone.toFile())
        def environment = TestChildEnvironment.cleared(builder)
        environment.put('PATH', path)
        def refusal = refusalOf(IOException) {
            Process started = ProcessLaunch.grouped(dir).start(builder, StdinSource.NONE)
            assert started.waitFor(30, TimeUnit.SECONDS)
        }
        def context = DoctorContext.of(new ProjectResolution.Registered(RegisteredCloneFixture.unregistered(dir.resolve('home'), clone)), {
            new PipelineReading.Loaded(definition, new StageBindings(false, true))
        })
        [readings: readings, context: context, refusal: refusal]
    }
}
