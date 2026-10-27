package com.github.oinsio.gnomish.app.doctor

import static com.github.oinsio.gnomish.app.doctor.IdentityRow.refusalOf

import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitVersionCheck
import com.github.oinsio.gnomish.app.ConfigurationViolations
import com.github.oinsio.gnomish.app.ConfigurationViolationsException
import com.github.oinsio.gnomish.app.OperatorConfigLoaderHarness
import com.github.oinsio.gnomish.app.VersionCommand
import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.app.port.git.GitVersionRefusedException
import com.github.oinsio.gnomish.app.project.ProjectResolution
import com.github.oinsio.gnomish.gittransfer.GitVersion
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path

/**
 * The identity rows of the checks that need no project's law (M1 of add-doctor-command): the
 * factory version, the git floor, the operator configuration, the project registration and the
 * pre-registry layout. The startup side is the owner {@code take} runs before anything else: the
 * version line, {@link GitVersionCheck#verify}, and the operator configuration loader in its
 * refusing form, all over the fixture the doctor side reads.
 */
final class HostIdentityRows {

    private HostIdentityRows() {}

    static List<IdentityRow> all() {
        [
            new IdentityRow(DoctorCheckId.FACTORY_VERSION, { Path dir ->
                [factoryVersion()]
            }),
            new IdentityRow(DoctorCheckId.GIT_FLOOR, { Path dir ->
                [
                    'version-2-56-0',
                    'version-2-55',
                    'version-broken'
                ].collect {
                    gitFloor(dir, it)
                }
            }),
            new IdentityRow(DoctorCheckId.CONFIGURATION, { Path dir ->
                [
                    configuration(dir, 'clean', ''),
                    configuration(dir, 'unknown-key', 'factory:\n  git-netwrok-timeout: 5m\n')
                ]
            }),
            new IdentityRow(DoctorCheckId.REGISTRATION, { Path dir ->
                [
                    registration(dir, true),
                    registration(dir, false)
                ]
            }),
            new IdentityRow(DoctorCheckId.LEGACY_LAYOUT, { Path dir ->
                [
                    legacyLayout(dir, false),
                    legacyLayout(dir, true)
                ]
            }),
        ]
    }

    // No startup path refuses on the version: it is information (FR1). The identity is the value —
    // the doctor line names the version `gnomish --version` prints, read from one FactoryVersion.
    private static IdentityRow.Case factoryVersion() {
        def console = new ScriptedConsoleIO()
        assert VersionCommand.answer(['--version'] as String[], console)
        String printed = console.printed.join('').trim()
        new IdentityRow.Case('this build', false, printed,
                new FactoryVersionDoctorCheck().run(DoctorSpecSupport.withoutProject()), [printed])
    }

    // The startup refusal is the git floor owner's refusing form, with its ERROR line; the doctor
    // line carries the installed version and the floor's reason, or what git printed instead.
    private static IdentityRow.Case gitFloor(Path dir, String preset) {
        def runner = new GitProcessRunner(StandIn.link(dir.resolve("git-${preset}"), preset).toString())
        def logs = LogCaptureSupport.attach(GitVersionCheck)
        GitVersionRefusedException refusal
        String logged
        try {
            refusal = refusalOf(GitVersionRefusedException) {
                new GitVersionCheck(runner).verify()
            }
            logged = logs.list*.formattedMessage.join('\n')
        } finally {
            logs.detach()
        }
        assert logged.contains(OperatorEvent.STARTUP_GIT_VERSION_REFUSED.head()) == (refusal != null)
        def doctor = new GitFloorDoctorCheck(new GitVersionCheck(runner)).run(DoctorSpecSupport.withoutProject())
        Map<String, List<String>> shared = [
            'version-2-56-0': ['2.56.0'],
            'version-2-55': [
                '2.55.2',
                GitVersion.FLOOR_REASON
            ],
            'version-broken': [
                StandIn.data('stderr#git-broken-install').readLines()[0].trim()
            ],
        ]
        new IdentityRow.Case(preset, refusal != null, refusal?.message ?: logged, doctor, shared[preset])
    }

    // `take`'s loader refuses on every violation; doctor's collect mode publishes the same lines.
    private static IdentityRow.Case configuration(Path dir, String label, String hostYaml) {
        def harness = new OperatorConfigLoaderHarness(dir.resolve(label))
        def widgets = harness.gitTree('widgets')
        harness.register('widgets', widgets)
        if (hostYaml) {
            harness.hostConfig(hostYaml)
        }
        def refusal = refusalOf(ConfigurationViolationsException) {
            harness.load(command('take', widgets))
        }
        def loaded = harness.load(command('doctor', widgets))
        def doctor = new ConfigurationDoctorCheck(loaded.context.getBeanProvider(ConfigurationViolations))
                .run(DoctorSpecSupport.withoutProject())
        IdentityRow.Case.refusing(label, refusal, doctor, refusal == null ? [] : refusal.violations())
    }

    // `take` on an unregistered --dir is refused by the registry, through the loader; doctor
    // reports the registry's own refusal of the same directory.
    private static IdentityRow.Case registration(Path dir, boolean registered) {
        def harness = new OperatorConfigLoaderHarness(dir.resolve(registered ? 'registered' : 'unregistered'))
        def widgets = harness.gitTree('widgets')
        harness.register('widgets', widgets)
        def target = registered ? widgets : harness.gitTree('elsewhere')
        def refusal = refusalOf(ConfigurationViolationsException) {
            harness.load(command('take', target))
        }
        def loaded = harness.load(command('doctor', target))
        def context = DoctorContext.of(loaded.context.getBeanProvider(ProjectResolution).getIfAvailable(), {
            new PipelineReading.Unloaded(['not read by this row'], 'none')
        })
        IdentityRow.Case.refusing(registered ? 'a registered clone' : 'an unregistered directory', refusal,
                new RegistrationDoctorCheck().run(context), refusal == null ? [] : refusal.violations())
    }

    // No startup path reads the pre-registry entries (design D4: no other code answers them), so
    // none refuses on them: the identity is that `take`'s startup proceeds over a home holding
    // them while doctor only warns, never fails.
    private static IdentityRow.Case legacyLayout(Path dir, boolean leftover) {
        def harness = new OperatorConfigLoaderHarness(dir.resolve(leftover ? 'leftover' : 'clean'))
        def widgets = harness.gitTree('widgets')
        harness.register('widgets', widgets)
        if (leftover) {
            Files.createDirectories(harness.home.root().resolve('serve'))
        }
        def refusal = refusalOf(ConfigurationViolationsException) {
            harness.load(command('take', widgets))
        }
        def doctor = new LegacyLayoutDoctorCheck(harness.home).run(DoctorSpecSupport.withoutProject())
        assert doctor*.status().every {
            it == (leftover ? DoctorStatus.WARN : DoctorStatus.OK)
        }
        IdentityRow.Case.refusing(leftover ? 'a leftover serve folder' : 'a clean home', refusal, doctor, [])
    }

    private static List<String> command(String name, Path dir) {
        [
            name,
            "--dir=${dir}".toString()
        ]
    }
}
