package com.github.oinsio.gnomish.gitobjects

import com.github.oinsio.gnomish.testfixtures.sourcescan.SourceMarkerScan
import java.nio.file.Path
import spock.lang.Specification

/**
 * NFR-S1, NG3 of fix-lifecycle-push: {@code gitobjects} stays hermetic — the library reads and
 * writes a bare object database through git PLUMBING only, and gains no network operation from any
 * change that adds push points above it. Push, and every remote read that feeds it, is the
 * factory-side adapter's monopoly; a task environment drives this library, so a network verb
 * appearing here would put remote access — and the credentials the remote answers to — inside the
 * box the gnome runs in.
 *
 * <p>A source scan, for the same reason {@code RemotePrimitiveSingleSiteSpec} and {@code
 * NoForcePushGuardSpec} use one in {@code adapter.git}: these are string arguments handed to the
 * {@code git} subprocess, invisible to the package-dependency rules {@code GitObjectsBoundarySpec}
 * checks — that spec pins which TYPES this library may reach, and this one pins which git
 * SUBCOMMANDS it may run.
 *
 * <p>Implements NFR-S1 of fix-lifecycle-push; FR25 of add-sandbox-core.
 */
class NoNetworkCommandGuardSpec extends Specification {

    /**
     * The whole production source tree, not the Java package alone: the {@code groovy} convention
     * plugin gives every module a {@code src/main/groovy} source set too, so a network verb added in
     * any JVM language the build compiles must be in scope of the scan.
     */
    private static final Path SOURCES = Path.of('src/main')

    /** The extensions {@link #SOURCES} may hold production code in; anything else is not scanned. */
    private static final List<String> SOURCE_EXTENSIONS = ['.java', '.groovy', '.kt']

    /**
     * Every git subcommand that talks to a remote, as it would have to be spelled to reach {@code
     * GitExec.run} — a quoted argv token, so prose mentioning {@code fetch} in a comment is not a
     * hit while an actual {@code List.of("fetch", ...)} is.
     */
    private static final List<String> NETWORK_VERBS = [
        'clone',
        'fetch',
        'fetch-pack',
        'ls-remote',
        'pull',
        'push',
        'remote',
        'send-pack',
        'submodule',
        'upload-pack',
    ]

    /**
     * The source files whose text contains {@code marker}, as file names. Delegates to the
     * shared {@link SourceMarkerScan}, which {@code RemotePrimitiveSingleSiteSpec} in {@code
     * adapter.git} also uses for the same reason.
     */
    private static List<String> filesContaining(String marker) {
        SourceMarkerScan.filesContaining(SOURCES, SOURCE_EXTENSIONS, marker)
    }

    /**
     * The positive control: every assertion below is an empty-list check, which a scanner that has
     * stopped matching anything at all — a moved source root, a filter that excludes the real
     * extension, a quoting change in how subcommands are spelled — passes silently. Pinning a verb
     * the library provably does run makes such a break red instead of green.
     */
    def "the scan really matches quoted git subcommands in this library's sources"() {
        expect:
        filesContaining('"update-ref"') == ['CommitBuilder.java']
    }

    def "no git subprocess in gitobjects can reach a remote: #verb"() {
        expect:
        filesContaining("\"${verb}\"") == []

        where:
        verb << NETWORK_VERBS
    }

    // The other half of "no network operation": the library reaches no remote through the JDK
    // either, so the guard cannot be walked around by opening a socket instead of spawning git.
    def "gitobjects opens no network connection of its own"() {
        expect:
        filesContaining('java.net.') == []
    }
}
