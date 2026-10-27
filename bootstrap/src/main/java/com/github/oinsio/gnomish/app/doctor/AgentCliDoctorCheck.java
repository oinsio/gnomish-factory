package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.app.DoctorProjectReadings;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.subprocess.ExecutableVerdict;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * The agent CLI binary is locatable when a stage is host-bound (FR1 of add-doctor-command): the
 * launch owner's {@code assessExecutable}, the rule its launch refuses on, asked for {@code
 * factory.agent-cli-binary} with the {@code PATH} a host agent round hands its child — so {@code
 * doctor} reads no {@code PATH} and holds no lookup rule itself (design D4). Skipped when no stage
 * binds the host adapter (design D3). A child {@code PATH} that cannot be composed is thrown on to
 * {@link DoctorCommand}, the one owner of a check that could not run.
 *
 * <p>Implements FR1, FR4 of add-doctor-command.
 */
@NullMarked
@Component
final class AgentCliDoctorCheck implements DoctorCheck {

    private final HostLaunchAssessment launch;
    private final DoctorProjectReadings readings;
    private final FactoryProperties factoryProperties;

    /**
     * @param launch the launch owner's assessments
     * @param readings the owners the round's child environment is composed through
     * @param factoryProperties read for {@code factory.agent-cli-binary}
     */
    AgentCliDoctorCheck(
            HostLaunchAssessment launch, DoctorProjectReadings readings, FactoryProperties factoryProperties) {
        this.launch = launch;
        this.readings = readings;
        this.factoryProperties = factoryProperties;
    }

    @Override
    public DoctorCheckId id() {
        return DoctorCheckId.AGENT_CLI;
    }

    @Override
    public List<DoctorVerdict> run(DoctorContext context) {
        return context.onHost(id(), (clone, definition) -> List.of(locate(clone, definition)));
    }

    private DoctorVerdict locate(RegisteredClone clone, PipelineDefinition definition) {
        String binary = factoryProperties.agentCliBinary();
        return switch (launch.executable(binary, clone.clonePath(), readings.childPath(definition))) {
            case ExecutableVerdict.Resolved resolved ->
                new DoctorVerdict(id(), DoctorStatus.OK, binary + " resolves to " + resolved.executable(), null);
            case ExecutableVerdict.Unresolved unresolved ->
                new DoctorVerdict(
                        id(),
                        DoctorStatus.FAIL,
                        unresolved.reason(),
                        "install the agent CLI on the PATH an agent round receives, or set"
                                + " factory.agent-cli-binary to its absolute path");
        };
    }
}
