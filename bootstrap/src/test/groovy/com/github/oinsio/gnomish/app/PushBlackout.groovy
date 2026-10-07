package com.github.oinsio.gnomish.app

import java.nio.file.Path

/**
 * The push half of the "killed after the outcome commit" window ({@link RunKillPoint#AFTER_PARK_COMMIT}):
 * a {@code SIGKILL} between a lifecycle commit and its push leaves the commit in the factory clone and
 * origin behind. In process the same durable state is reached by letting the write's own best-effort
 * push run while {@code origin} points nowhere — the decorator's push fails into its one WARN and
 * returns normally (NFR-R1 of fix-lifecycle-push), so nothing reaches origin — and restoring the
 * remote before the simulated death propagates.
 *
 * <p>The remote is redirected through git's own configuration rather than by removing the origin
 * repository, so the same mechanism serves the bare-origin host fixtures and the Gitea-backed
 * container ones alike.
 */
final class PushBlackout {

    private PushBlackout() {}

    /**
     * Runs {@code write} with {@code origin} of {@code cloneDir} pointing at a repository that does
     * not exist, then puts the real URL back whatever {@code write} did.
     *
     * @param cloneDir the factory clone whose pushes must not reach origin during the write
     * @param write the lifecycle write whose push is to be lost
     */
    static void around(Path cloneDir, Closure write) {
        String url = git(cloneDir, 'remote', 'get-url', 'origin')
        git(cloneDir, 'remote', 'set-url', 'origin', cloneDir.resolveSibling('no-such-origin.git').toString())
        try {
            write()
        } finally {
            git(cloneDir, 'remote', 'set-url', 'origin', url)
        }
    }

    private static String git(Path cwd, String... args) {
        def process = new ProcessBuilder((['git'] + (args as List<String>)) as String[])
        .directory(cwd.toFile())
        .redirectErrorStream(true)
        .start()
        String output = new String(process.inputStream.readAllBytes(), 'UTF-8').trim()
        assert process.waitFor() == 0: "git ${args.join(' ')} failed: ${output}"
        output
    }
}
