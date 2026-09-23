package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.gittransfer.GitTransfer;
import com.github.oinsio.gnomish.gittransfer.TransferSource.SeedPath;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Builds the {@code docker} argv for the one-shot seed-clone helper (design D3, FR3 of
 * add-sandbox-core). Extracted from {@link DockerCommands} for file size; the behavior is
 * unchanged. The clone line of the helper's script is the transfer owner's {@code SeedPath} value,
 * rendered (FR7, design D6 of own-git-transfer-argv): the seed clone runs under the same
 * deny-by-default set, protocol allowlist, and configuration isolation as every factory fetch, and
 * this class holds no flag of its own.
 */
final class DockerSeedCloneCommand {

    /** Where the seed helper sees the factory clone; exists only inside that helper, never the task container. */
    static final String SEED_SOURCE = "/gnomish/src";

    /** The variable the owner's environment names the helper's throwaway global file through. */
    private static final String GLOBAL_CONFIG = "GIT_CONFIG_GLOBAL";

    /**
     * What an argv element or an environment value may spell to be emitted verbatim into the {@code
     * sh -c} literal: the owner's constants are option names, {@code key=value} pairs and absolute
     * paths, none of which the shell reads specially. Anything wider is refused at rendering rather
     * than quoted — quoting would be interpolation, the property the constant script exists to deny.
     */
    private static final Pattern SHELL_PLAIN = Pattern.compile("[A-Za-z0-9_./:=-]+");

    /** The owner's clone value for the helper's fixed source and destination paths (design D6). */
    private static final GitTransfer SEED_CLONE = GitTransfer.clone(
            new SeedPath(Path.of(SEED_SOURCE), Path.of(ContainerTaskExecutionEnvironment.WORKING_COPY)));

    // $1 = task branch, $2 (optional) = factory-chosen commit pin. Paths are constants; set -e
    // makes any failing step fail the helper, surfacing git's stderr through the run result.
    // safe.directory lets the in-box user read the read-only-mounted factory clone, which carries
    // the host uid. It must arrive via a global-scope config file: git through at least 2.43 (the
    // Ubuntu noble package) honors safe.directory ONLY from system/global scope — never from -c or
    // GIT_CONFIG_* — and the clone's child upload-pack would not see -c anyway. The global file
    // is the one the owner's environment names, so the export here and the `env` on the clone
    // line agree by construction: the script writes the entries into the file git will read.
    // Both the worktree path and its gitdir are listed: git resolves a non-bare source to
    // <path>/.git and refuses that exact path as dubious, so the worktree entry alone is not
    // enough on a real Linux bind mount (a macOS/Docker Desktop mount remaps ownership to the
    // container user and hides the mismatch entirely).
    // Idempotent by the .git guard: re-seeding a volume that already holds the clone (resume over
    // a surviving volume, FR6) changes nothing — except an explicit pin, which is always applied.
    private static final String SEED_SCRIPT = seedScript(SEED_CLONE);

    private DockerSeedCloneCommand() {}

    /**
     * The helper's constant script for one owner value: the {@code safe.directory} file the value's
     * {@code GIT_CONFIG_GLOBAL} names, then the value itself as {@code env -u K … K=V … git <argv>}
     * with the owner's branch placeholder emitted as the shell word {@code "$1"}. Package-private so
     * the spec can pin the production script against the owner's value and drive the two refusals.
     *
     * @throws IllegalArgumentException if the value names no global file, or an element of it would
     *     need shell quoting
     */
    static String seedScript(GitTransfer clone) {
        String globalFile = Optional.ofNullable(clone.environment().get(GLOBAL_CONFIG))
                .flatMap(entry -> entry)
                .orElseThrow(() -> new IllegalArgumentException("the seed clone value must set " + GLOBAL_CONFIG
                        + ": safe.directory is read from global scope only"));
        return """
                set -e
                if [ ! -d %s/.git ]; then
                  export %s=%s
                  git config --global --add safe.directory %s
                  git config --global --add safe.directory %s/.git
                  %s
                  cd %s
                  unset %s
                  git remote remove origin
                  git config user.name gnome
                  git config user.email gnome@sandbox.local
                  git config gc.auto 0
                fi
                cd %s
                if [ -n "${2:-}" ]; then git reset --hard "$2"; fi
                """.formatted(
                        ContainerTaskExecutionEnvironment.WORKING_COPY,
                        GLOBAL_CONFIG,
                        plain(globalFile),
                        SEED_SOURCE,
                        SEED_SOURCE,
                        cloneLine(clone),
                        ContainerTaskExecutionEnvironment.WORKING_COPY,
                        GLOBAL_CONFIG,
                        ContainerTaskExecutionEnvironment.WORKING_COPY);
    }

    /**
     * Unsets first, then assignments, then the argv: {@code env}'s option parsing (BusyBox's in the
     * reference image) stops at the first {@code NAME=VALUE} word, so a {@code -u} after one would
     * be handed to git.
     */
    private static String cloneLine(GitTransfer clone) {
        List<String> words = new ArrayList<>(List.of("env"));
        for (Map.Entry<String, Optional<String>> entry : clone.environment().entrySet()) {
            if (entry.getValue().isEmpty()) {
                words.add("-u");
                words.add(plain(entry.getKey()));
            }
        }
        for (Map.Entry<String, Optional<String>> entry : clone.environment().entrySet()) {
            if (entry.getValue().isPresent()) {
                words.add(plain(entry.getKey()) + "=" + plain(entry.getValue().get()));
            }
        }
        words.add("git");
        for (String element : clone.argv()) {
            words.add(GitTransfer.BRANCH_PARAMETER.equals(element) ? "\"$1\"" : plain(element));
        }
        return String.join(" ", words);
    }

    private static String plain(String word) {
        if (!SHELL_PLAIN.matcher(word).matches()) {
            throw new IllegalArgumentException(
                    "a seed clone word would need shell quoting, which the constant script never does: " + word);
        }
        return word;
    }

    /**
     * The one-shot seed clone (design D3, FR3): a throwaway {@code run --rm}
     * helper — <em>not</em> the task container — that mounts the factory clone
     * read-only beside the task volume and runs the owner's seed clone from one
     * into the other, so the task container itself never sees the factory
     * clone, its remote address, or any credential. The owner's value carries
     * {@code --no-local --no-hardlinks} (a same-filesystem clone would otherwise
     * share object files with the factory repository, letting in-box corruption
     * reach it below git's own mechanics; the transport path also validates every
     * object, FR7 of own-git-transfer-argv), {@code --single-branch --no-tags}
     * (other tasks' refs and the operator's tags stay out of the box's namespace),
     * and a protocol allowlist of {@code file} alone. The clone gets the agent
     * identity and {@code gc.auto 0} (a one-shot clone needs no background
     * repacking), and {@code origin} is removed — harvest fetches from the
     * environment factory-side, so the box needs no remote at all. {@code
     * --network none}: a local clone needs no network. The optional
     * factory-chosen {@code commitPin} resets the working copy to that commit of
     * the task branch (fresh-box verification, sandboxed judge boxes, {@code
     * --discard-work}).
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
