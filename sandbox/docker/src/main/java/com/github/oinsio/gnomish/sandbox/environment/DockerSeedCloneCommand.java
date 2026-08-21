package com.github.oinsio.gnomish.sandbox.environment;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Builds the {@code docker} argv for the one-shot seed-clone helper (design D3, FR3 of
 * add-sandbox-core). Extracted from {@link DockerCommands} for file size; the behavior is
 * unchanged.
 */
final class DockerSeedCloneCommand {

    /** Where the seed helper sees the factory clone; exists only inside that helper, never the task container. */
    static final String SEED_SOURCE = "/gnomish/src";

    // $1 = task branch, $2 (optional) = factory-chosen commit pin. Paths are constants; set -e
    // makes any failing step fail the helper, surfacing git's stderr through the run result.
    // safe.directory lets the in-box user read the read-only-mounted factory clone, which carries
    // the host uid. It must arrive via a global-scope config file: git through at least 2.43 (the
    // Ubuntu noble package) honors safe.directory ONLY from system/global scope — never from -c or
    // GIT_CONFIG_* — and a local clone consults it in a child upload-pack, which -c would not reach
    // anyway. GIT_CONFIG_GLOBAL points that global scope at a throwaway file inside the one-shot
    // helper, so no image-owned config is touched and nothing survives the container.
    // Both the worktree path and its gitdir are listed: git resolves a non-bare source to
    // <path>/.git and refuses that exact path as dubious, so the worktree entry alone is not
    // enough on a real Linux bind mount (a macOS/Docker Desktop mount remaps ownership to the
    // container user and hides the mismatch entirely).
    // Idempotent by the .git guard: re-seeding a volume that already holds the clone (resume over
    // a surviving volume, FR6) changes nothing — except an explicit pin, which is always applied.
    private static final String SEED_SCRIPT = """
            set -e
            if [ ! -d %s/.git ]; then
              export GIT_CONFIG_GLOBAL=/tmp/gnomish-seed-gitconfig
              git config --global --add safe.directory %s
              git config --global --add safe.directory %s/.git
              git clone --no-hardlinks --single-branch --branch "$1" %s %s
              cd %s
              unset GIT_CONFIG_GLOBAL
              git remote remove origin
              git config user.name gnome
              git config user.email gnome@sandbox.local
              git config gc.auto 0
            fi
            cd %s
            if [ -n "${2:-}" ]; then git reset --hard "$2"; fi
            """.formatted(
                    ContainerTaskExecutionEnvironment.WORKING_COPY,
                    SEED_SOURCE,
                    SEED_SOURCE,
                    SEED_SOURCE,
                    ContainerTaskExecutionEnvironment.WORKING_COPY,
                    ContainerTaskExecutionEnvironment.WORKING_COPY,
                    ContainerTaskExecutionEnvironment.WORKING_COPY);

    private DockerSeedCloneCommand() {}

    /**
     * The one-shot seed clone (design D3, FR3): a throwaway {@code run --rm}
     * helper — <em>not</em> the task container — that mounts the factory clone
     * read-only beside the task volume and runs {@code git clone --no-hardlinks}
     * from one into the other, so the task container itself never sees the
     * factory clone, its remote address, or any credential. {@code
     * --no-hardlinks} is mandatory: without it a same-filesystem clone shares
     * object files with the factory repository, letting in-box corruption reach
     * it below git's own mechanics. {@code --single-branch} keeps other tasks'
     * refs out of the box's namespace. The clone gets the agent identity and
     * {@code gc.auto 0} (a one-shot clone needs no background repacking), and
     * {@code origin} is removed — harvest fetches from the environment
     * factory-side, so the box needs no remote at all. {@code --network none}:
     * a local clone needs no network. The optional factory-chosen {@code
     * commitPin} resets the working copy to that commit of the task branch
     * (fresh-box verification, sandboxed judge boxes, {@code --discard-work}).
     *
     * <p>The script is a constant; branch and pin reach it as positional
     * parameters, never interpolated, so neither can alter the script.
     */
    static List<String> seedClone(
            String key,
            String image,
            String sourceClone,
            String branch,
            @Nullable String pin,
            ObjectOwnership ownership) {
        List<String> argv = new ArrayList<>(List.of("run", "--rm"));
        argv.addAll(FactoryDockerLabels.ownershipLabelArgs(key, ownership));
        argv.addAll(List.of(
                "--network",
                "none",
                "-v",
                sourceClone + ":" + SEED_SOURCE + ":ro",
                "-v",
                FactoryDockerLabels.volumeName(key) + ":" + ContainerTaskExecutionEnvironment.WORKING_COPY,
                image,
                "sh",
                "-c",
                SEED_SCRIPT,
                "gnomish",
                branch));
        if (pin != null) {
            argv.add(pin);
        }
        return List.copyOf(argv);
    }
}
