package com.github.oinsio.gnomish.sandbox.environment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one owner of the image-declared-volume override set (design D2, FR1–FR3 of
 * fix-image-declared-volumes): the ordered list of paths an image's Dockerfile
 * declares with {@code VOLUME} that the factory does <em>not</em> already mount
 * explicitly, rendered as the {@code tmpfs} mount fragments a {@code docker run}
 * argv appends.
 *
 * <p>Why it exists: Docker has no flag to ignore an image's {@code VOLUME}
 * declarations (moby/moby#43190), so a declared path with no explicit mount gets
 * an <em>anonymous</em> volume — an unlabelled object the {@code sandbox-lifecycle}
 * ownership model cannot see, outliving the container it was made for. Occupying
 * each such path with a memory-backed mount is the only recognized way to prevent
 * one, and it creates no Docker object at all: the sweep matrix, the reaper and the
 * crash-consistency shape set are unchanged.
 *
 * <p>The value is what makes the mechanism single-owner: {@link
 * GuardCommands#runGuard} and {@code DockerCommands.runContainer} take it as a
 * typed parameter, so a {@code run} builder that skipped the override does not
 * compile. Nothing here names a path of any particular image — the set is read
 * from the image at creation time (G2).
 *
 * <p><b>The one path that does not come through here</b>, recorded as the single-owner
 * exemption this mechanism is allowed ({@code implementation.md}, old-way sweep): the Testcontainers
 * E2E fixtures that start third-party images the factory does not run in production —
 * {@code GiteaContainerFixture} ({@code gitea/gitea}, which declares {@code /data}) and
 * {@code GiteaActionsRunnerFixture} ({@code gitea/act_runner}, which declares nothing). They
 * are test infrastructure, not factory containers, so the {@code execution-environment}
 * requirement does not reach them; and the anonymous volume they cause cannot outlive its
 * container, because Testcontainers' reaper removes the container with {@code docker rm -f -v}.
 * The mechanism exists for objects that survive their container, which these do not. A fixture
 * that ever stops being reaped — a container started outside Testcontainers, or one kept alive
 * past the JVM — leaves this exemption and needs the override, via {@code withTmpFs}.
 *
 * <p>Implements FR1, FR2, FR3, NFR-R1, NFR-R2 of fix-image-declared-volumes.
 *
 * @param paths the declared paths to override, in the order the runtime reported
 *     them; never null, already stripped of the factory's explicit destinations
 */
@UntrustedParser
record DeclaredVolumeOverrides(List<String> paths) {

    /**
     * The size bound on every override, a constant rather than a knob (design D5):
     * the guard container has no {@code --memory} limit, so this is what caps it,
     * and 64 MB sits far above what a declared path legitimately holds (mitmproxy's
     * generated CA set is 24 KB) and far below the box's own memory limit. A tool
     * that needs more at a declared path needs its content in the image or under the
     * working copy instead. See {@link #TMPFS_MODE} for the other half of the mount.
     */
    static final String TMPFS_SIZE = "64m";

    /**
     * The mode on every override, likewise a constant (design D5). It is not cosmetic: the
     * runtime copies the declared directory's <em>mode</em> from the image onto the tmpfs but
     * never its <em>owner</em> — the mount comes up {@code root:root} (runc's tmpfs path has no
     * {@code chown}; moby/moby#39466), while the anonymous volume this class exists to prevent
     * copied owner and mode alike. Without this, an image whose non-root user owned its declared
     * path — the factory's own {@code gnome} — would lose the write access it had before the
     * override. {@code --mount type=tmpfs} exposes no uid/gid, so the mode is the only lever;
     * world-writable plus the sticky bit is what Kubernetes gives {@code emptyDir} and what
     * {@code /tmp} has always been. The runtime's own {@code nosuid,nodev,noexec} defaults stay
     * in force on top of it.
     *
     * <p>Honoured by runc 1.1.8 and newer (opencontainers/runc#3912): older runtimes restore the
     * image directory's mode after mounting and silently ignore this. {@code
     * ContainerDeclaredVolumesSpec} is the gate that reports such a host.
     */
    static final String TMPFS_MODE = "1777";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    DeclaredVolumeOverrides {
        paths = List.copyOf(paths);
    }

    /**
     * The override set for one container: the image's declared volume paths minus the
     * destinations this container already mounts explicitly (the working copy in the
     * box, the read-only config directory in the guard), which keep their own mount.
     *
     * <p>A read that cannot be trusted fails the caller rather than yielding an empty
     * set (NFR-R1, design D4): an empty set on failure would silently recreate the very
     * leak this class exists to close. The caller wraps the throw into its own
     * infrastructure-failure signal, which is where the runtime-outage policy lives.
     *
     * @param docker the runtime seam to ask; never null
     * @param image the image reference the container will run; never blank
     * @param explicitDestinations the in-container paths this container mounts itself
     * @return the overrides; never null, empty when the image declares nothing new
     * @throws DockerCommandFailedException if the runtime refused the inspect or answered a
     *     shape other than {@code null} or a JSON object of paths
     */
    static DeclaredVolumeOverrides resolve(DockerCli docker, String image, Set<String> explicitDestinations) {
        DockerResult result = docker.run(DockerCommands.inspectImageVolumes(image));
        if (!result.ok()) {
            throw new DockerCommandFailedException("image inspect", image, null, result.stderr());
        }
        List<String> overridden = new ArrayList<>();
        for (String declared : declaredPaths(image, result.stdout())) {
            if (!explicitDestinations.contains(declared)) {
                overridden.add(declared);
            }
        }
        return new DeclaredVolumeOverrides(overridden);
    }

    /**
     * The keys of {@code .Config.Volumes}, in the runtime's own order. Docker writes the
     * field as a JSON object keyed by path, or {@code null} when the image declares none;
     * both are normal answers and anything else is a shape this parser refuses to guess at
     * (fail closed, design D4).
     */
    private static List<String> declaredPaths(String image, UntrustedText answer) {
        JsonNode node;
        try {
            // @UntrustedParser warrant (design D11): docker's inspect answer becomes the list of
            //     declared volume paths, which travel into a `docker run` argv as mount
            //     destinations and are never rendered to a reader; the answer itself leaves this
            //     class only through the carrier's log exit, in the messages below.
            node = MAPPER.readTree(answer.forParsing());
        } catch (JsonProcessingException e) {
            throw new DockerCommandFailedException(
                    "image inspect",
                    image,
                    null,
                    UntrustedText.subprocess("unparseable declared volumes: " + answer),
                    e);
        }
        if (node.isNull()) {
            return List.of();
        }
        if (!node.isObject()) {
            throw new DockerCommandFailedException(
                    "image inspect",
                    image,
                    null,
                    UntrustedText.subprocess("declared volumes of an unexpected shape: " + answer));
        }
        List<String> declared = new ArrayList<>();
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            declared.add(property.getKey());
        }
        return declared;
    }

    /**
     * The argv fragments a {@code run} builder appends after its own explicit mounts:
     * one bounded {@code tmpfs} mount per overridden path, in declaration order. Every
     * fragment is memory-backed and carries no {@code source=} — the override introduces
     * no host path and no named object (NFR-S1) — and carries {@link #TMPFS_MODE}, without
     * which the image's own non-root user could not write to a path it owned before.
     *
     * @return the fragments; never null, empty when there is nothing to override
     */
    List<String> argv() {
        List<String> argv = new ArrayList<>();
        for (String path : paths) {
            argv.add("--mount");
            argv.add("type=tmpfs,destination=" + path + ",tmpfs-size=" + TMPFS_SIZE + ",tmpfs-mode=" + TMPFS_MODE);
        }
        return List.copyOf(argv);
    }

    /** Whether the image declared nothing the factory does not already mount itself. */
    boolean isEmpty() {
        return paths.isEmpty();
    }

    /**
     * The operator-facing rendering for the lifecycle anchor (NFR-O1, design D7): the
     * overridden paths, or the word {@code none} — stated explicitly rather than left as
     * an empty bracket pair, so "no declared volumes" reads as an answer and not as a
     * missing field.
     */
    String describe() {
        return paths.isEmpty() ? "none" : paths.toString();
    }
}
