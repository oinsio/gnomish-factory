package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.gittransfer.GitTransfer;
import com.github.oinsio.gnomish.gittransfer.Refspec;
import com.github.oinsio.gnomish.gittransfer.TransferSource.Container;
import com.github.oinsio.gnomish.sandbox.environment.ContainerHarvest;
import com.github.oinsio.gnomish.sandbox.environment.ContainerTaskExecutionEnvironment;
import com.github.oinsio.gnomish.sandbox.environment.DockerUnavailableException;
import com.github.oinsio.gnomish.subprocess.Termination;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.util.Optional;

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
 * +} prefix, so git itself refuses a rewritten history (fast-forward-only).
 * Everything else about the fetch — no tag auto-following, no {@code
 * FETCH_HEAD}, no submodule recursion, object validation, the {@code ext}-only
 * protocol allowlist that is what enables the transport, and full isolation
 * from the operator's git configuration — is the transfer owner's ({@link
 * GitTransfer#fetch} for a {@link Container} source, design D2 of
 * own-git-transfer-argv); this class chooses the source and the refspec and
 * can vary nothing else. The fetch updates a ref that is never checked out
 * factory-side (FR17), and runs through {@link GitProcessRunner}'s typed
 * entry, which serializes it with every other mutation of the same clone.
 *
 * <p>Failure classification: an object git's validation refused throws
 * {@link HarvestRefusedException} naming it (a boundary violation of the box,
 * FR5 of own-git-transfer-argv), as does a fast-forward refusal (the
 * history-rewrite violation, a quality signal); an unreachable docker daemon
 * throws {@link DockerUnavailableException} (an infrastructure failure, no
 * attempt burned, NFR-R1); anything else throws {@link HarvestFailedException}.
 *
 * <p>Implements FR5 of add-sandbox-core; FR4, FR5, FR6 of own-git-transfer-argv.
 *
 * @param runner the git subprocess runner, shared with the run's other git-adapter machinery
 * @param cloneDir the factory clone the branch is fetched into; git commands run with this path
 *     as {@code cwd}
 */
@UntrustedParser
public record ContainerHarvestFetch(GitProcessRunner runner, Path cloneDir) implements ContainerHarvest {

    @Override
    public void fetch(String containerName, String branch) {
        GitCommandResult result = runner.run(
                cloneDir, GitTransfer.fetch(new Container(url(containerName)), new Refspec(refspec(branch))));
        if (result.termination() != Termination.EXITED) {
            // A fetch killed on its deadline or cut short by a shutdown printed at most a partial
            // transcript, and git writes its "non-fast-forward" refusal at the very end — so
            // classifying that transcript would grade an unanswered fetch as a history-rewrite
            // violation. It is a plain harvest failure, named as unfinished (FR7).
            throw new HarvestFailedException(
                    branch,
                    "the harvest fetch "
                            + (result.termination() == Termination.TIMED_OUT
                                    ? "was cut off on its deadline"
                                    : "was interrupted before it finished"),
                    result.stderr());
        }
        if (result.exitCode() != 0) {
            throw classify(branch, result.stderr());
        }
    }

    /**
     * The {@code ext::} transport URL: git runs the command after {@code ext::}
     * and speaks the pack protocol over its stdio, substituting {@code %S} with
     * the service name ({@code git-upload-pack} for a fetch). The transport is
     * enabled by the owner's protocol allowlist for a {@link Container} source,
     * which names {@code ext} and nothing else.
     */
    static String url(String containerName) {
        return "ext::docker exec -i " + containerName + " %S " + ContainerTaskExecutionEnvironment.WORKING_COPY;
    }

    /** The factory-fixed refspec, no {@code +} prefix — fast-forward-only by git's own rules (FR5). */
    static String refspec(String branch) {
        return branch + ":" + branch;
    }

    /**
     * Maps a failed fetch to its failure class by git's stderr: an object that
     * failed validation is a boundary violation of the box (asked first, through
     * the one parser of that grammar, design D4 of own-git-transfer-argv), a
     * fast-forward refusal is the history-rewrite violation, a daemon outage is
     * infrastructure, everything else is a plain harvest failure.
     */
    static RuntimeException classify(String branch, UntrustedText stderr) {
        Optional<FetchRefusal> refusal = FetchRefusal.parse(stderr);
        if (refusal.isPresent()) {
            return HarvestRefusedException.objectValidation(branch, refusal.get());
        }
        if (DockerUnavailableException.reportsDaemonUnreachable(stderr.forParsing())) {
            return new DockerUnavailableException("docker daemon is unreachable during harvest", stderr);
        }
        if (stderr.contains("non-fast-forward")) {
            return new HarvestRefusedException(branch, stderr);
        }
        return new HarvestFailedException(branch, stderr);
    }
}
