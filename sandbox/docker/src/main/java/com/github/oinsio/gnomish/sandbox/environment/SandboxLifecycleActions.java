package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The two Docker actions {@link SandboxLifecycleDecision} takes on an unowned object: stop (by the
 * object's own name — safe for any role, never touches a sibling) and dispose. Guard and
 * seed-helper share their environment key's namespace with an unrelated main box (same key), so a
 * bundled {@link TaskEnvironmentDisposal#dispose} would wrongly destroy it too — only the one named
 * object is removed for those two roles, and an {@link ObjectRole#UNRECOGNIZED} object is removed
 * by its own name and kind for the same reason (its name matches no factory pattern, so the key's
 * triple is somebody else's). Judge and verification own an exclusive {@code -j}/{@code -v} key, so
 * the whole triple is disposed together; a main box owns its key exclusively too.
 *
 * <p>Every action reports whether it actually happened (NFR-R1, `sandbox-lifecycle` "No verdict is
 * distinct from no claims"): a non-zero {@code docker} exit is a failed action, never a silent
 * success the verdict and the ledger would then report as {@code disposed}. The key-triple path
 * runs through the best-effort port, which reports nothing, so its outcome is read back from the
 * object itself — still present means the dispose did not take.
 */
final class SandboxLifecycleActions {

    private static final Logger log = LoggerFactory.getLogger(SandboxLifecycleActions.class);

    private final DockerCli docker;
    private final TaskEnvironmentDisposal disposal;

    SandboxLifecycleActions(DockerCli docker, TaskEnvironmentDisposal disposal) {
        this.docker = docker;
        this.disposal = disposal;
    }

    /**
     * Disposes {@code object}, by its own name for the roles that share a key and by the whole
     * key triple otherwise.
     *
     * @return whether the object is gone afterwards
     */
    boolean dispose(SandboxLifecycleClassification c, ListedDockerObject object) {
        if (c.role() == ObjectRole.GUARD || c.role() == ObjectRole.SEED_HELPER) {
            return succeeded(() -> docker.run(DockerCommands.removeContainer(object.name())), "removal", object);
        }
        if (c.role() == ObjectRole.UNRECOGNIZED) {
            return succeeded(() -> docker.run(remove(object)), "removal", object);
        }
        bestEffort(() -> disposal.dispose(c.environmentKey()), "dispose of " + c.environmentKey());
        return gone(object);
    }

    /**
     * Stops the container by its own name.
     *
     * @return whether {@code docker stop} reported success
     */
    boolean stop(ListedDockerObject object) {
        return succeeded(() -> docker.run(DockerCommands.stop(object.name())), "stop", object);
    }

    private boolean succeeded(Supplier<DockerResult> action, String what, ListedDockerObject object) {
        DockerResult result = action.get();
        if (result.ok()) {
            return true;
        }
        log.warn(
                "{} of {} failed (exit {}): {}",
                what,
                object.name(),
                result.exitCode(),
                result.stderr().strip());
        return false;
    }

    /** Whether the object is gone: its own {@code inspect} no longer resolves the name. */
    private boolean gone(ListedDockerObject object) {
        if (!docker.run(DockerLifecycleCommands.inspectExists(object.kind(), object.name()))
                .ok()) {
            return true;
        }
        log.warn("dispose of {} left it in place", object.name());
        return false;
    }

    private static List<String> remove(ListedDockerObject object) {
        return switch (object.kind()) {
            case CONTAINER -> DockerCommands.removeContainer(object.name());
            case VOLUME -> DockerCommands.removeVolume(object.name());
            case NETWORK -> DockerCommands.removeNetwork(object.name());
        };
    }

    private void bestEffort(Runnable action, String what) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.debug("best-effort {} failed: {}", what, e.toString());
        }
    }
}
