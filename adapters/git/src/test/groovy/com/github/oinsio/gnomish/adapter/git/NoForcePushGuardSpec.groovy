package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR7, design D6 of add-claim-heartbeat: the task branch is NEVER force-pushed by any party — the
 * git non-fast-forward refusal is the hard zombie fence, so of two writers holding the same task
 * the late pusher gets a persist refusal and follows the normal abort path. This guard pins that
 * invariant at the source: it scans every {@code adapter.git} Java source for git {@code push}
 * invocations and asserts none carries a forcing flag ({@code --force}, {@code --force-with-lease},
 * {@code -f}) or a {@code +}-prefixed forced refspec. A future edit that tries to sneak a force onto
 * any task-branch push reds this spec.
 *
 * <p>A source scan (not ArchUnit) is the right mechanism: the forcing flag is a string argument to
 * the {@code git} subprocess, invisible to bytecode-level analysis. The scan is scoped to actual
 * {@code push} invocations, so unrelated legitimate {@code --force} uses ({@code git worktree
 * remove --force}) are not matched.
 *
 * <p>Implements FR7, D6 of add-claim-heartbeat.
 */
class NoForcePushGuardSpec extends Specification {

    private static final Path ADAPTER_GIT_SOURCES =
    Path.of('src/main/java/com/github/oinsio/gnomish/adapter/git')

    /** Every {@code .run(...)} call in {@code adapter.git} whose arguments include the {@code "push"} subcommand. */
    private static List<PushCall> pushInvocations() {
        assert Files.isDirectory(ADAPTER_GIT_SOURCES):
        "adapter.git source directory not found at ${ADAPTER_GIT_SOURCES.toAbsolutePath()} — is the test running from the project root?"
        def calls = []
        // Walk, not list: a push added under a subpackage (today adapter.git.state) must be seen.
        Files.walk(ADAPTER_GIT_SOURCES).withCloseable { stream ->
            stream.filter { it.toString().endsWith('.java') }.forEach { file ->
                def text = Files.readString(file)
                extractRunCalls(text).each { call ->
                    if (call.contains('"push"')) {
                        calls << new PushCall(file.fileName.toString(), call)
                    }
                }
            }
        }
        calls
    }

    /** Extracts each {@code .run( ... )} call body with balanced parentheses (handles multi-line calls). */
    private static List<String> extractRunCalls(String text) {
        def results = []
        int idx = 0
        while ((idx = text.indexOf('.run(', idx)) >= 0) {
            int open = idx + '.run('.length() - 1
            int depth = 0
            int i = open
            while (i <text.length()) {
                char c = text.charAt(i)
                if (c == '(' as char) {
                    depth++
                } else if (c == ')' as char) {
                    depth--
                    if (depth == 0) {
                        break
                    }
                }
                i++
            }
            results << text.substring(open, Math.min(i + 1, text.length()))
            idx = i + 1
        }
        results
    }

    private record PushCall(String file, String call) {}

    /**
     * FR7, D6 of add-claim-heartbeat: the scan must actually find the task-branch push, else the
     * guard is vacuous. M3 of fix-lifecycle-push sharpens it to a single site: design D2 collapsed
     * the three inline copies into the shared {@link RefspecPush}, so the push command now has
     * exactly one construction site in production — a second one appearing here is the duplication
     * this change removed growing back.
     */
    def "M3: the push command has exactly one construction site, and the scan finds it"() {
        expect:
        pushInvocations().collect { it.file() } == ['RefspecPush.java']
    }

    // FR7, D6: no git push in adapter.git may ever force — the non-fast-forward fence must stand.
    def "no git push invocation in adapter.git uses a forcing flag or forced refspec"() {
        expect:
        pushInvocations().every { PushCall push ->
            def call = push.call()
            !call.contains('--force') &&
                    !call.contains('"-f"') &&
                    !(call =~ /"\+/) &&
                    !(call =~ /"\+refs/)
        }

        and: 'name any offender explicitly for a readable failure'
        def offenders = pushInvocations().findAll { PushCall push ->
            def call = push.call()
            call.contains('--force') || call.contains('"-f"') || (call =~ /"\+/)
        }
        offenders.collect { it.file() } == []
    }
}
