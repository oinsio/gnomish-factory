package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitVersionCheck
import com.github.oinsio.gnomish.adapter.git.HooksPathLeftover
import com.github.oinsio.gnomish.app.doctor.AgentCliDoctorCheck
import com.github.oinsio.gnomish.app.doctor.AgentCredentialDoctorCheck
import com.github.oinsio.gnomish.app.doctor.ConfigurationDoctorCheck
import com.github.oinsio.gnomish.app.doctor.DockerDoctorCheck
import com.github.oinsio.gnomish.app.doctor.DoctorCommand
import com.github.oinsio.gnomish.app.doctor.DoctorCommandFixture
import com.github.oinsio.gnomish.app.doctor.DoctorSurvey
import com.github.oinsio.gnomish.app.doctor.FactoryVersionDoctorCheck
import com.github.oinsio.gnomish.app.doctor.GitFloorDoctorCheck
import com.github.oinsio.gnomish.app.doctor.GuardImageDoctorCheck
import com.github.oinsio.gnomish.app.doctor.HooksPathDoctorCheck
import com.github.oinsio.gnomish.app.doctor.LauncherHelperDoctorCheck
import com.github.oinsio.gnomish.app.doctor.LegacyLayoutDoctorCheck
import com.github.oinsio.gnomish.app.doctor.PipelineDoctorCheck
import com.github.oinsio.gnomish.app.doctor.ProcessLaunchAssessment
import com.github.oinsio.gnomish.app.doctor.RefFormatDoctorCheck
import com.github.oinsio.gnomish.app.doctor.RegistrationDoctorCheck
import com.github.oinsio.gnomish.app.doctor.SandboxImageDoctorCheck
import com.github.oinsio.gnomish.app.doctor.SecretsDoctorCheck
import com.github.oinsio.gnomish.app.doctor.TrackerReadProbe
import com.github.oinsio.gnomish.app.port.console.ConsoleIO
import com.github.oinsio.gnomish.app.port.run.SandboxImageReadiness
import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectRegistry
import com.github.oinsio.gnomish.app.project.ProjectResolution
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition

/**
 * The {@code doctor} command as the context assembles it (design D3 of add-doctor-command): every
 * {@code DoctorCheck} bean over the graph's own owners, and the directory's resolution as the
 * configuration loader would publish it for {@code --dir} — computed when the command first asks,
 * since a spec's clone may become a working tree only after the runner is built.
 */
final class DoctorFixture {

    private DoctorFixture() {}

    /** The pieces of the application graph the doctor checks ask. */
    static DoctorCommand command(Map<String, Object> graph) {
        def home = graph.home as FactoryHome
        def clonePath = graph.clonePath as Path
        def sandbox = graph.sandboxProperties as SandboxProperties
        def imageReadiness = graph.imageReadiness as SandboxImageReadiness
        def preconditions = graph.preconditions as ServePreconditions
        def readings = new DoctorProjectReadings(graph.trackerWiring as TrackerWiring, graph.checks as CheckEquipment,
                graph.bindingProperties as BindingProperties, graph.bindingRegistry as AdapterBindingRegistry, sandbox)
        def launch = new ProcessLaunchAssessment()
        def checks = [
            new FactoryVersionDoctorCheck(),
            new GitFloorDoctorCheck(graph.gitVersionCheck as GitVersionCheck),
            new ConfigurationDoctorCheck(provider(ConfigurationViolations) {
                ConfigurationViolations.NONE
            }),
            new RegistrationDoctorCheck(),
            new RefFormatDoctorCheck(preconditions),
            new PipelineDoctorCheck(),
            new SecretsDoctorCheck(readings, graph.secretLocator as SecretLocator),
            new AgentCredentialDoctorCheck(readings),
            new DockerDoctorCheck(preconditions),
            new SandboxImageDoctorCheck(preconditions, sandbox),
            new GuardImageDoctorCheck(imageReadiness, sandbox),
            new AgentCliDoctorCheck(launch, readings, graph.factoryProperties as FactoryProperties),
            new LauncherHelperDoctorCheck(launch),
            new LegacyLayoutDoctorCheck(home),
            new HooksPathDoctorCheck(new HooksPathLeftover(new GitProcessRunner())),
            new TrackerReadProbe(readings),
        ]
        def resolution = provider(ProjectResolution) {
            resolution(home, clonePath)
        }
        new DoctorCommand(checks, new DoctorSurvey(resolution, readings),
                graph.resolvedClone as ObjectProvider<RegisteredClone>, graph.console as ConsoleIO,
                DoctorCommandFixture.logFileEnvironment(home.hostLogFile()))
    }

    /** What the loader publishes for {@code --dir=clonePath}: the registry's one answer. */
    static ProjectResolution resolution(FactoryHome home, Path clonePath) {
        def dir = clonePath.toAbsolutePath().normalize()
        if (Files.exists(dir.resolve('.git'))) {
            return new ProjectResolution.Registered(RegisteredCloneFixture.resolvedOrRegistered(home.root(), dir))
        }
        try {
            new ProjectResolution.Registered(ProjectRegistry.scan(home).resolve(dir))
        } catch (UsageException e) {
            new ProjectResolution.Unregistered(dir, true, e.message)
        }
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, Closure<T> value) {
        def beans = new DefaultListableBeanFactory()
        beans.registerBeanDefinition(type.simpleName, new RootBeanDefinition(type, {
            -> value.call()
        }))
        beans.getBeanProvider(type)
    }
}
