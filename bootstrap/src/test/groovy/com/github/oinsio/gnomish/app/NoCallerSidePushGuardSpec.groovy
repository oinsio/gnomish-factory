package com.github.oinsio.gnomish.app

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR6, M3 of fix-lifecycle-push: exactly one code path owns the push-after-lifecycle-commit rule —
 * the {@code PushBestEffortTaskRepository} decorator the container bundle wraps its repository in.
 * Before this change the container termination path pushed by hand after each terminal outcome; the
 * two calls were deleted when the decorator was wired, and a re-added one would mean two owners and
 * a double push at every terminal boundary.
 *
 * <p>The scan covers the WHOLE composition root, recursively, and matches any push call rather than
 * one helper's name: the failure mode is "a caller pushes again", which a hand-rolled {@code
 * RefspecPush}, a second helper, or a call in a sibling class would all reproduce. Exactly one
 * caller-side push is allowed to exist, and it is named below.
 *
 * <p>A source scan for the same reason the git adapter's own guards use one: the rule is about which
 * call sites exist, not about behavior a single run can observe.
 */
class NoCallerSidePushGuardSpec extends Specification {

    private static final Path BOOTSTRAP_SOURCES = Path.of('src/main/java')

    /**
     * The one caller-side push the design keeps (FR15 of add-tracker-port): revocation salvage is
     * an in-box commit plus a cloneDir-rooted push with no worktree and no lifecycle write behind
     * it, so no repository decorator can own it. Keyed by the file and the enclosing method, so
     * moving it or adding a second push to the same file still reds this spec.
     */
    private static final List<String> ALLOWED = [
        'ContainerRunSupport.java#revocationSalvageAndPush'
    ]

    /** Every push invocation in the composition root's production sources, as {@code file#method}. */
    private static List<String> pushSites() {
        assert Files.isDirectory(BOOTSTRAP_SOURCES):
        "bootstrap source root not found at ${BOOTSTRAP_SOURCES.toAbsolutePath()} — is the test running from the module root?"
        List<String> sites = []
        Files.walk(BOOTSTRAP_SOURCES).withCloseable { stream ->
            stream.filter { it.toString().endsWith('.java') }.forEach { file ->
                sites.addAll(pushSitesIn(file.fileName.toString(), Files.readString(file)))
            }
        }
        (List<String>) sites.sort()
    }

    /**
     * Scans one source's statement lines (comments and javadoc dropped, so the prose explaining why
     * a boundary does NOT push is not itself a hit) for a call whose name is or ends in "push", and
     * labels each with the last method signature seen above it.
     */
    private static List<String> pushSitesIn(String fileName, String text) {
        List<String> sites = []
        String method = '<unknown>'
        text.eachLine { String line ->
            def stripped = line.trim()
            if (stripped.startsWith('*') || stripped.startsWith('//') || stripped.startsWith('/*')) {
                return
            }
            def signature = stripped =~ /(?:public|protected|private|static|final|\s)*[\w<>\[\],.?\s]+\s(\w+)\s*\([^;]*\)\s*\{/
            if (signature.find()) {
                method = signature.group(1)
            }
            // A call ON something whose name carries "push" (push.pushBestEffort(...), a hand-rolled
            // helper's .push(...)), or a raw git push assembled here rather than in the adapter.
            // Constructing a decorator type — new PushBestEffortAttemptPersistence(...) — is not a
            // push and has no receiver dot, so it is not matched.
            if (stripped =~ /\.\s*\w*[Pp]ush\w*\s*\(/ || stripped.contains('"push"')) {
                sites << "${fileName}#${method}".toString()
            }
            return
        }
        sites
    }

    def "the composition root holds no push beyond the one the design keeps"() {
        expect:
        pushSites() == ALLOWED
    }

    def "the scan is not vacuous — it finds the revocation salvage push it allows"() {
        expect:
        !pushSites().isEmpty()
    }
}
