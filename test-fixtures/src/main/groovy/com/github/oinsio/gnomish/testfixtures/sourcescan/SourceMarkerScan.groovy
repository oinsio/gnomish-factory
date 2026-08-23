package com.github.oinsio.gnomish.testfixtures.sourcescan

import java.nio.file.Files
import java.nio.file.Path

/**
 * Shared source-scan helper for single-construction-site / no-network-command guard specs
 * (e.g. {@code RemotePrimitiveSingleSiteSpec}, {@code NoNetworkCommandGuardSpec}, {@code
 * NoForcePushGuardSpec}): each needs to search a module's own production source tree for the
 * file names whose source text contains a marker string — a git subcommand token, or the literal
 * name of a construction, that is invisible to bytecode-level analysis and can only be checked as
 * a source-text scan.
 */
class SourceMarkerScan {

    private SourceMarkerScan() {
    }

    /**
     * The files under {@code root} whose text contains {@code marker}, as file names, sorted.
     * Only files whose name ends with one of {@code extensions} are scanned; the whole tree under
     * {@code root} is walked, not just its top directory, so a marker reappearing under a
     * subpackage is caught the same as one at the top.
     */
    static List<String> filesContaining(Path root, List<String> extensions, String marker) {
        assert Files.isDirectory(root):
        "source directory not found at ${root.toAbsolutePath()} — is the test running from the module directory?"
        List<String> hits = []
        Files.walk(root).withCloseable { stream ->
            stream.filter { file ->
                extensions.any { file.toString().endsWith(it) }
            }.forEach { file ->
                if (Files.readString(file).contains(marker)) {
                    hits << file.fileName.toString()
                }
            }
        }
        hits.sort()
    }
}
