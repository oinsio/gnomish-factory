package com.github.oinsio.gnomish.app.doctor

import static com.github.oinsio.gnomish.app.doctor.IdentityRow.refusalOf

import com.github.oinsio.gnomish.adapter.agent.AgentCredentials
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.DoctorProjectReadings.StageBindings
import com.github.oinsio.gnomish.app.PipelineLoadOutcome
import com.github.oinsio.gnomish.app.PipelineStartup
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.RunArguments
import com.github.oinsio.gnomish.app.TaskGitFixture
import com.github.oinsio.gnomish.app.TaskSource
import com.github.oinsio.gnomish.app.port.git.CloneRefFormatRefusedException
import com.github.oinsio.gnomish.app.port.run.SandboxImageReadiness
import com.github.oinsio.gnomish.app.port.workspace.WorkspaceFactory
import com.github.oinsio.gnomish.app.project.ProjectResolution
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.beans.factory.support.DefaultListableBeanFactory

/**
 * The identity rows of the checks that read the registered clone or its law (M1 of
 * add-doctor-command): the ref backend, the pipeline law and the agent credential. The startup
 * side is the owner the startup path calls — the ref-format check every take bootstrap runs on a
 * real clone, the pipeline startup load, the round environment's composition — over the medium the
 * doctor check reads.
 */
final class ProjectIdentityRows implements BareGitRepoFixture {

    List<IdentityRow> all() {
        [
            new IdentityRow(DoctorCheckId.REF_FORMAT, { Path dir ->
                [
                    refFormat(dir, 'reftable'),
                    refFormat(dir, 'files')
                ]
            }),
            new IdentityRow(DoctorCheckId.PIPELINE, { Path dir ->
                [
                    pipeline(dir, 'valid', 'stages:\n  - build\n'),
                    pipeline(dir, 'invalid', '{}\n')
                ]
            }),
            new IdentityRow(DoctorCheckId.SECRETS, { Path dir ->
                [
                    SecretsIdentityRow.secrets(dir, true),
                    SecretsIdentityRow.secrets(dir, false)
                ]
            }),
            new IdentityRow(DoctorCheckId.AGENT_CREDENTIAL, { Path dir ->
                [
                    agentCredential([ANTHROPIC_API_KEY: 'key-value']),
                    agentCredential([:])
                ]
            }),
        ]
    }

    // Every take bootstrap runs `requireRefFormat` on the clone before its first write
    // (RunnerStartHardeningSpec); doctor asks the startup owner of the preconditions on the same clone.
    private IdentityRow.Case refFormat(Path dir, String backend) {
        Path clone = initWorkingRepo(dir, "clone-${backend}")
        if (backend == 'files') {
            gitOutput(clone, 'refs', 'migrate', '--ref-format=files')
        }
        def git = TaskGitFixture.real()
        def refusal = refusalOf(CloneRefFormatRefusedException) {
            git.branches().requireRefFormat(clone)
        }
        def preconditions = PreconditionsFixture.preconditions(git.branches(), [:] as SandboxImageReadiness)
        def registered = RegisteredCloneFixture.unregistered(dir.resolve('home'), clone)
        def doctor = new RefFormatDoctorCheck(preconditions).run(DoctorContext.of(new ProjectResolution.Registered(registered), {
            new PipelineReading.Unloaded(['not read by this row'], 'none')
        }))
        IdentityRow.Case.refusing("a ${backend} clone", refusal, doctor, refusal == null ? [] : [refusal.message])
    }

    // `run`'s startup load and doctor's survey read the same working tree through one source.
    private static IdentityRow.Case pipeline(Path dir, String label, String pipelineYaml) {
        Path project = dir.resolve(label)
        write(project, 'config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\n')
        write(project, 'pipeline.yaml', pipelineYaml)
        write(project, 'stages/build/instructions.md', 'build it\n')
        write(project, 'stages/build/stage.yaml', 'purpose: build it\nexecutor:\n  type: agent-cli\n  model: model-x\n'
                + 'instructions: stages/build/instructions.md\nadvancement: auto\n')
        def source = TrackerValidatorStub.plainSource()
        def outcome = new PipelineStartup(source, { Path root ->
            new DirectoryWorkspace(root)
        } as WorkspaceFactory)
        .load(new RunArguments(project, new TaskSource.Inline('t'), null, null, RunArguments.Mode.GIT, null, null, false, null))
        List<String> errors = outcome instanceof PipelineLoadOutcome.Failed ? outcome.renderedErrors() : []
        def beans = new DefaultListableBeanFactory()
        beans.registerSingleton('resolution',
                new ProjectResolution.Registered(RegisteredCloneFixture.unregistered(dir.resolve('home'), project)))
        def context = new DoctorSurvey(beans.getBeanProvider(ProjectResolution),
                DoctorSpecSupport.readings([:], [source: source])).survey()
        new IdentityRow.Case(label, !errors.isEmpty(), errors.join('\n'), new PipelineDoctorCheck().run(context), errors)
    }

    // No startup path refuses on a missing agent credential: the round starts without one. The
    // identity is the round's own value — doctor fails exactly when the environment an agent round
    // composes resolves none of the credential names, and names the ones it does resolve.
    private static IdentityRow.Case agentCredential(Map<String, String> secrets) {
        def readings = DoctorSpecSupport.readings(secrets)
        def definition = DoctorSpecSupport.definition()
        Map<String, String> round = readings.roundChildEnv(definition).resolve(AgentCredentials.names())
        def context = DoctorContext.of(new ProjectResolution.Registered(DoctorSpecSupport.CLONE), {
            new PipelineReading.Loaded(definition, new StageBindings(true, false))
        })
        def doctor = new AgentCredentialDoctorCheck(readings).run(context)
        assert !doctor*.detail().join('\n').contains('key-value')
        new IdentityRow.Case(secrets ? 'a credential set' : 'no credential', round.isEmpty(),
                round.keySet().join(', '), doctor, round.keySet() as List)
    }

    private static void write(Path project, String relative, String text) {
        Path file = project.resolve('.gnomish').resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }
}
