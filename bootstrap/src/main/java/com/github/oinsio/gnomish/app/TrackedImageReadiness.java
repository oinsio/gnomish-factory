package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.OriginRemote;
import com.github.oinsio.gnomish.app.git.ProjectIdentity;
import com.github.oinsio.gnomish.app.port.run.SandboxImageReadiness;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import com.github.oinsio.gnomish.sandbox.environment.DockerImageContractInspection;
import com.github.oinsio.gnomish.sandbox.environment.DockerImageReadiness;
import com.github.oinsio.gnomish.sandbox.environment.ObjectOwnership;
import com.github.oinsio.gnomish.sandbox.environment.OwnershipMode;
import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The {@link SandboxImageReadiness} the {@code serve} startup assessment asks (design D17 of
 * add-sandbox-base-image): the Docker adapter's checks, with the floor probe's helper stamped
 * {@code tracked} and with the project identity every box of the daemon's clone carries — resolved
 * the way {@link ContainerRunSupportFactory#create} resolves it, so the sandbox lifecycle sweep
 * scoped to this project recognizes a helper a killed process left behind.
 *
 * <p>The clone is read when the assessment runs, never at construction: the bean exists only once
 * the configuration loader resolved a project (design D9 of add-project-registry). The contract
 * inspection alone (design D1 of add-doctor-command) starts no helper, so it reads no clone and
 * resolves no ownership: it is the adapter's {@link DockerImageContractInspection}, the same half
 * {@link DockerImageReadiness#assess} runs first.
 *
 * <p>Implements FR13 of add-sandbox-base-image; FR1, NFR-R1 of add-doctor-command.
 */
final class TrackedImageReadiness implements SandboxImageReadiness {

    private final ObjectProvider<RegisteredClone> registeredClone;
    private final SandboxProperties sandboxProperties;
    private final FactoryProperties factoryProperties;

    /**
     * @param registeredClone the clone {@code --dir} names, read lazily
     * @param sandboxProperties supplies the configured project identity
     * @param factoryProperties supplies the git network and docker command bounds
     */
    TrackedImageReadiness(
            ObjectProvider<RegisteredClone> registeredClone,
            SandboxProperties sandboxProperties,
            FactoryProperties factoryProperties) {
        this.registeredClone = registeredClone;
        this.sandboxProperties = sandboxProperties;
        this.factoryProperties = factoryProperties;
    }

    @Override
    public void inspectContract(String image) {
        new DockerImageContractInspection(factoryProperties.dockerCommandTimeout()).inspect(image);
    }

    @Override
    public void inspectPresence(String image) {
        new DockerImageContractInspection(factoryProperties.dockerCommandTimeout()).inspectPresence(image);
    }

    @Override
    public void assess(String image) {
        new DockerImageReadiness(factoryProperties.dockerCommandTimeout(), ownership()).assess(image);
    }

    /** The ownership the probe's helper carries: {@code tracked}, under the clone's project identity. */
    ObjectOwnership ownership() {
        Path cloneDir = registeredClone.getObject().clonePath();
        var runner = new GitProcessRunner(factoryProperties.gitNetworkTimeout());
        String projectId = ProjectIdentity.resolve(
                sandboxProperties.projectId(), new OriginRemote(runner).url(cloneDir), cloneDir);
        return new ObjectOwnership(OwnershipMode.TRACKED, projectId);
    }
}
