package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.app.port.git.BoxGitVersionRefusedException;
import com.github.oinsio.gnomish.gittransfer.GitVersion;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The box's git version floor, checked before every seed clone (design D13 of tighten-spec-contract,
 * as amended 2026-10-13). The seed clone runs in a one-shot helper on the sandbox image, with the
 * image's own git, and reads the factory clone — which is on the reftable ref backend, so a git
 * older than 2.45 cannot even open it, and which the clone reads over the local transport whose
 * hook protections {@link GitVersion#FLOOR} stands for. The box shares the factory's one floor
 * (2.51) rather than keeping a lower one of its own: one owner, one number. An image below the floor is refused here,
 * before any network, volume or container exists, with a reason naming the image and the remedy,
 * instead of failing later as an opaque seed-clone error.
 *
 * <p>The rule is the host's ({@code GitVersionCheck} in the git adapter): one floor and one parser,
 * both owned by the transfer leaf's {@link GitVersion}; this class only asks a different git —
 * {@code docker run --rm --network none <image> git --version}, stamped with the environment's
 * ownership labels so a helper the process loses is swept like the seed helper. It is checked on
 * every materialize that seeds, not once per process: an image tag can be rebuilt between boxes.
 *
 * <p>The one ERROR of this failure class is logged here; the command that ends on the exception
 * prints its message and nothing else.
 *
 * <p>Implements NFR-S2 of tighten-spec-contract (findings F122, F135).
 */
@UntrustedParser
final class BoxGitFloor {

    private static final Logger log = LoggerFactory.getLogger(BoxGitFloor.class);

    /** Why the box needs the floor: the factory clone's ref store, then the host floor's reason. */
    static final String REASON =
            "it opens the factory clone's reftable ref store (readable since git 2.45), and " + GitVersion.FLOOR_REASON;

    private final DockerCli docker;
    private final String key;
    private final ObjectOwnership ownership;

    /**
     * @param docker the docker subprocess seam; never null
     * @param key the environment key whose labels the probe helper carries; never blank
     * @param ownership the mode and project identity stamped on the probe helper
     */
    BoxGitFloor(DockerCli docker, String key, ObjectOwnership ownership) {
        this.docker = docker;
        this.key = key;
        this.ownership = ownership;
    }

    /**
     * Refuses {@code image} unless its git reports a version at or above {@link GitVersion#FLOOR}.
     *
     * @param image the sandbox image the seed clone is about to run on; never blank
     * @throws BoxGitVersionRefusedException below the floor, or when the image's git reports no
     *     version (a missing git, a failed run: its stderr is what the refusal quotes)
     */
    void require(String image) {
        DockerResult result = docker.run(versionProbe(image));
        Optional<GitVersion> reported =
                result.ok() ? GitVersion.parse(result.stdout().forParsing()) : Optional.empty();
        if (reported.isPresent() && !reported.get().isBelow(GitVersion.FLOOR)) {
            return;
        }
        UntrustedText printed = result.ok() ? result.stdout() : result.stderr();
        log.error(
                OperatorEvent.BOX_GIT_VERSION_REFUSED.head()
                        + "sandbox image refused for environment {}: image {} needs git {} or newer (printed: {})",
                key,
                image,
                GitVersion.FLOOR,
                printed.forLog());
        throw new BoxGitVersionRefusedException(image, GitVersion.FLOOR.toString(), printed, REASON);
    }

    /** The one-shot probe: the image's own git, no network, the environment's ownership labels. */
    List<String> versionProbe(String image) {
        List<String> argv = new ArrayList<>(List.of("run", "--rm"));
        argv.addAll(FactoryDockerLabels.ownershipLabelArgs(key, ownership));
        argv.addAll(List.of("--network", "none", image, "git", "--version"));
        return List.copyOf(argv);
    }
}
