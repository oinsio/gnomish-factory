package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.testfixtures.sourcescan.SourceMarkerScan
import java.nio.file.Path
import spock.lang.Specification

/**
 * M3, design D2 of fix-lifecycle-push: each of the factory's three remote primitives — the origin
 * presence/URL read, the remote-refs tip read, and the push command — has exactly ONE construction
 * site in production. The duplication this change removed (three inline {@code originConfigured}
 * copies plus an {@code OriginRemoteUrl} variant, three inline push commands, one buried {@code
 * ls-remote}) is what let three tiers of replication be added over the years without any of them
 * sharing mechanics; this scan reds the moment a fourth copy appears.
 *
 * <p>A source scan for the same reason {@link NoForcePushGuardSpec} uses one (which owns the push
 * command's own single-site assertion): these are string arguments to the {@code git} subprocess,
 * invisible to bytecode-level analysis.
 */
class RemotePrimitiveSingleSiteSpec extends Specification {

    private static final Path ADAPTER_GIT_SOURCES = Path.of('src/main/java/com/github/oinsio/gnomish/adapter/git')

    /**
     * The files whose source text contains {@code marker}, as file names. Walks the whole package
     * tree, not just its top directory: a fourth copy added under a subpackage (today {@code
     * adapter.git.state}) is exactly the regrowth this scan exists to catch. Delegates to the
     * shared {@link SourceMarkerScan}, which {@code NoNetworkCommandGuardSpec} in {@code
     * gitobjects} also uses for the same reason.
     */
    private static List<String> filesContaining(String marker) {
        SourceMarkerScan.filesContaining(ADAPTER_GIT_SOURCES, ['.java'], marker)
    }

    def "the origin presence/URL read is constructed in exactly one place"() {
        expect:
        filesContaining('"remote", "get-url"') == ['OriginRemote.java']
    }

    def "the remote-refs tip read is constructed in exactly one place"() {
        expect:
        filesContaining('"ls-remote"') == ['RemoteBranchTip.java']
    }

    def "the remote name itself is spelled in exactly one place"() {
        expect:
        filesContaining('String NAME = "origin"') == ['OriginRemote.java']
    }
}
