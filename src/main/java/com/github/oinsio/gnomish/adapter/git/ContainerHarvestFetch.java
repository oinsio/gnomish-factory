package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.environment.ContainerHarvest;
import com.github.oinsio.gnomish.adapter.environment.ContainerTaskExecutionEnvironment;
import com.github.oinsio.gnomish.adapter.environment.DockerUnavailableException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The git realization of {@link ContainerHarvest} (design D3, FR5): a
 * factory-side {@code git fetch} from the running task container into the
 * factory clone over git's {@code ext::} transport — the pack protocol spoken
 * through {@code docker exec -i <container> git-upload-pack /gnomish/work},
 * the docker twin of the {@code ssh://} fetch of the VM precedent and the git
 * daemon of Docker Sandboxes' clone mode. No filesystem is shared: bytes cross
 * as a pack stream on the exec pipe, and the factory-side {@code git fetch} is
 * the trusted end that parses it.
 *
 * <p>Security posture (FR5, NFR-S1): the URL and refspec are assembled from
 * factory-derived values only — the container name comes from the factory's
 * own naming scheme and the branch from the sanitized task id; nothing
 * produced inside the box is ever interpolated. The refspec carries no {@code
 * +} prefix, so git itself refuses a rewritten history (fast-forward-only);
 * {@code --no-recurse-submodules} keeps in-box submodule references from
 * triggering any further fetch. The fetch updates a ref that is never checked
 * out factory-side (FR17), and runs through {@link GitProcessRunner}, which
 * serializes it with every other mutation of the same clone.
 *
 * <p>Failure classification: a fast-forward refusal throws {@link
 * HarvestRefusedException} (the history-rewrite violation, a quality signal);
 * an unreachable docker daemon throws {@link DockerUnavailableException} (an
 * infrastructure failure, no attempt burned, NFR-R1); anything else throws
 * {@link HarvestFailedException}.
 *
 * <p>Implements FR5 of add-sandbox-core.
 *
 * @param runner the git subprocess runner, shared with the run's other git-adapter machinery
 * @param cloneDir the factory clone the branch is fetched into; git commands run with this path
 *     as {@code cwd}
 */
public record ContainerHarvestFetch(GitProcessRunner runner, Path cloneDir) implements ContainerHarvest {

    private static final String DAEMON_UNREACHABLE = "cannot connect to the docker daemon";

    @Override
    public void fetch(String containerName, String branch) {
        // protocol.ext.allow must be granted explicitly (git refuses the ext transport by
        // default); "user" — not "always" — so the grant covers exactly this direct invocation
        // and nothing git initiates on its own (e.g. a submodule fetch).
        GitCommandResult result = runner.run(
                cloneDir,
                "-c",
                "protocol.ext.allow=user",
                "fetch",
                "--no-recurse-submodules",
                url(containerName),
                refspec(branch));
        if (result.exitCode() != 0) {
            throw classify(branch, result.stderr());
        }
    }

    /**
     * The {@code ext::} transport URL: git runs the command after {@code ext::}
     * and speaks the pack protocol over its stdio, substituting {@code %S} with
     * the service name ({@code git-upload-pack} for a fetch). The transport is
     * enabled per invocation with {@code -c protocol.ext.allow=user} — git
     * refuses {@code ext::} by default, and the factory grants it only for this
     * one direct, factory-assembled fetch.
     */
    static String url(String containerName) {
        return "ext::docker exec -i " + containerName + " %S " + ContainerTaskExecutionEnvironment.WORKING_COPY;
    }

    /** The factory-fixed refspec, no {@code +} prefix — fast-forward-only by git's own rules (FR5). */
    static String refspec(String branch) {
        return branch + ":" + branch;
    }

    /**
     * Maps a failed fetch to its failure class by git's stderr: a
     * fast-forward refusal is the history-rewrite violation, a daemon outage is
     * infrastructure, everything else is a plain harvest failure.
     */
    static RuntimeException classify(String branch, String stderr) {
        if (stderr.toLowerCase(Locale.ROOT).contains(DAEMON_UNREACHABLE)) {
            return new DockerUnavailableException(
                    "docker daemon is unreachable during harvest: " + stderr.strip(), null);
        }
        if (stderr.contains("non-fast-forward")) {
            return new HarvestRefusedException(branch, stderr);
        }
        return new HarvestFailedException(branch, stderr);
    }
}
