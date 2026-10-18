package com.github.oinsio.gnomish.buildlogic

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

/**
 * The one way a build-side value source finds the tool it runs (design D14 of
 * fix-terminal-receipt-convergence as amended 2026-10-18, task 7.5; implements FR14 of
 * fix-terminal-receipt-convergence, NFR-R4 of scope-pit-locally): against the client's
 * {@code PATH}, read through {@code providers.environmentVariable('PATH')} as a parameter of the
 * source, and handed to {@code exec} as an absolute path.
 *
 * <p>The failure this exists for: a bare tool name in {@code exec} is resolved by the JDK against
 * the {@code PATH} the build daemon was started with, never the client's. A daemon an IDE or
 * launchd started ({@code /usr/bin:/bin:/usr/sbin:/sbin}) found no {@code docker} in
 * {@code /usr/local/bin}, so {@link DockerContextEndpoint} answered {@code Endpoint.NONE} without a
 * word, and {@link MutationScopeSource} ran Apple's {@code git} below the 2.51 floor.
 *
 * <p>A name holding a {@code /} is a path a caller chose and is taken as it is. A bare name is
 * looked up in each absolute entry of the {@code PATH}, in order, as {@code execvp} would, and
 * the first executable regular file wins; an empty or relative entry is skipped, since it would
 * resolve against the daemon's working directory. A tool that does not resolve is {@code null},
 * with a build warning naming the tool and the {@code PATH} searched — never a silent absence.
 */
final class ClientPathTool {

    private static final Logger LOGGER = Logging.getLogger(ClientPathTool)

    private ClientPathTool() {
    }

    /**
     * The absolute path of {@code tool} on {@code clientPath}, or {@code null} after a warning that
     * names both and ends with {@code withoutIt}, what the build does instead.
     */
    static String locate(String tool, String clientPath, String withoutIt) {
        String found = resolve(tool, clientPath)
        if (found == null) {
            // log-contract-exempt: a build warning on Gradle's console, not a line of the factory's
            // operator plane; this included build cannot reach the :operatorevent catalog. Pinned
            // by the unresolved branches of TestDockerEnvironmentFunctionalSpec and
            // MutationScopeGitPathFunctionalSpec, which assert the PATH it names.
            LOGGER.warn("${tool} was not found on the build's PATH (${clientPath ?: 'unset'}): ${withoutIt}")
        }
        found
    }

    /** {@link #locate} without the warning: the path, or {@code null}. */
    static String resolve(String tool, String clientPath) {
        if (tool.contains('/')) {
            return runnable(tool) ? tool : null
        }
        (clientPath ?: '').split(File.pathSeparator).findAll { it.startsWith('/') }.collect {
            "${it.endsWith('/') ? it : it + '/'}${tool}".toString()
        }.find { runnable(it) }
    }

    private static boolean runnable(String candidate) {
        try {
            Path path = Path.of(candidate)
            Files.isRegularFile(path) && Files.isExecutable(path)
        } catch (InvalidPathException ignored) {
            false
        }
    }
}
