package com.github.oinsio.gnomish.sandbox.environment;

import java.util.HashMap;
import java.util.Map;

/**
 * The one owner of the {@code docker exec} client's environment (design D3 of
 * fix-docker-exec-env-argv): subtractive, not an allowlist. The client keeps what it inherited —
 * its needs ({@code HOME}, {@code PATH}, the {@code DOCKER_*} family, proxies, certificates,
 * {@code SSH_AUTH_SOCK}) are install-specific and not the factory's to enumerate — minus the
 * declared credential names the launch withholds, plus the launch's composed environment, which
 * supplies the values of its value-less {@code -e NAME} flags.
 *
 * <p>The function is pure: it reads no process environment of its own (the caller hands in the
 * {@link ProcessBuilder}'s copy, so {@code System.getenv} stays with its one reader), and it
 * neither mutates nor returns the inherited map.
 *
 * <p>Implements FR4, NFR-S2 of fix-docker-exec-env-argv.
 */
final class DockerClientEnvironment {

    private DockerClientEnvironment() {}

    /**
     * Composes the client's environment for one streaming exec: a copy of {@code inherited}, the
     * launch's withheld names removed, then every entry of the launch's environment laid on top —
     * so a composed entry the run forwards on purpose wins over a withheld name of the same
     * spelling.
     *
     * @param inherited the environment the client would otherwise inherit; not modified
     * @param launch the exec whose withheld names and composed environment apply
     * @return a new map; never the inherited one
     */
    static Map<String, String> compose(Map<String, String> inherited, DockerExecLaunch launch) {
        Map<String, String> composed = new HashMap<>(inherited);
        composed.keySet().removeAll(launch.withheld());
        composed.putAll(launch.environment());
        return composed;
    }
}
