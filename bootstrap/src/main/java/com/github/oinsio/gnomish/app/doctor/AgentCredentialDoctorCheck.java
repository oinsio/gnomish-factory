package com.github.oinsio.gnomish.app.doctor;

import com.github.oinsio.gnomish.adapter.agent.AgentCredentials;
import com.github.oinsio.gnomish.app.DoctorProjectReadings;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * An agent credential is present when a stage is container-bound (FR1 of add-doctor-command):
 * {@link AgentCredentials#presentNames}, over the child-environment allowlist an agent round of
 * the clone's pipeline composes, so a credential counts exactly when it would reach the agent CLI.
 * Names only (NFR-S1). Skipped when no stage binds the container adapter (design D3). An allowlist
 * that cannot be composed — a passthrough name that is a declared credential, an unregistered
 * tracker type — is thrown on to {@link DoctorCommand}, the one owner of a check that could not run.
 *
 * <p>Implements FR1, FR4, NFR-S1 of add-doctor-command.
 */
@NullMarked
@Component
final class AgentCredentialDoctorCheck implements DoctorCheck {

    private final DoctorProjectReadings readings;

    /** @param readings the owners the round's allowlist is composed through */
    AgentCredentialDoctorCheck(DoctorProjectReadings readings) {
        this.readings = readings;
    }

    @Override
    public DoctorCheckId id() {
        return DoctorCheckId.AGENT_CREDENTIAL;
    }

    @Override
    public List<DoctorVerdict> run(DoctorContext context) {
        return context.onContainer(id(), (clone, definition) -> List.of(present(definition)));
    }

    private DoctorVerdict present(PipelineDefinition definition) {
        List<String> present = AgentCredentials.presentNames(readings.roundChildEnv(definition));
        if (present.isEmpty()) {
            return new DoctorVerdict(
                    id(),
                    DoctorStatus.FAIL,
                    "no agent credential reaches an agent round",
                    "set one of " + String.join(", ", AgentCredentials.names()));
        }
        return new DoctorVerdict(id(), DoctorStatus.OK, String.join(", ", present) + " present", null);
    }
}
