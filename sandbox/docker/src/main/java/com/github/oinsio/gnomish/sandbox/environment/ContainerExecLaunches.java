package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.subprocess.StdinSource;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one producer of {@link DockerExecLaunch} values for one container environment (design D2
 * of fix-docker-exec-env-argv): it holds the container key, the in-box working directory and the
 * withheld credential names — fixed when the environment is built — and builds each exec's argv
 * from the keys of the very map it pairs the argv with, so "flag names == delivered keys" holds by
 * construction. No caller beside it reaches {@link DockerCommands#exec} for a streaming exec (the
 * one exemption is the materializer's scratch {@code mkdir}, a management command, D4).
 *
 * <p>Pure: no live box, no network, no I/O.
 *
 * <p>Implements FR3, NFR-S4 of fix-docker-exec-env-argv.
 */
final class ContainerExecLaunches {

    private final String key;
    private final String workdir;
    private final Set<String> withheld;

    /**
     * The producer for one container.
     *
     * @param key the container key; never null
     * @param workdir the in-box working directory each exec runs in; never null
     * @param withheld the allowlist's declared credential names, withheld from the docker client's
     *     environment; never null
     */
    ContainerExecLaunches(String key, String workdir, Set<String> withheld) {
        this.key = key;
        this.workdir = workdir;
        this.withheld = Set.copyOf(withheld);
    }

    /**
     * The container key, for a diagnostic that names the box an exec ran in (the file channel's
     * truncation warning); never an input to an argv outside {@link #launch}.
     *
     * @return the container key; never null
     */
    String key() {
        return key;
    }

    /**
     * One exec's launch: the argv names exactly {@code env}'s keys, in its iteration order, and the
     * launch carries {@code env} itself as the client environment's values.
     *
     * @param env the composed variables to forward into the box; never null, may be empty
     * @param stdin the child's input; {@code -i} is added exactly when it carries input
     * @param argv the in-box command; never null
     * @return the launch; never null
     */
    DockerExecLaunch launch(Map<String, String> env, StdinSource stdin, List<String> argv) {
        return new DockerExecLaunch(DockerCommands.exec(key, workdir, env.keySet(), stdin, argv), env, withheld);
    }
}
