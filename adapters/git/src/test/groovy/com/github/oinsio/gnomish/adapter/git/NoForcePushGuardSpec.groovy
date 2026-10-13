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

    /** The transfer owner's push builder, the one construction site of a push argv (FR13 of tighten-spec-contract). */
    private static final Path PUSH_OWNER =
    Path.of('../../gittransfer/src/main/java/com/github/oinsio/gnomish/gittransfer/GitPush.java')

    /**
     * FR7, D6 of add-claim-heartbeat; M3 of fix-lifecycle-push: the push command has exactly one
     * construction site. Since tighten-spec-contract (design D13) that site is the transfer owner's
     * {@code GitPush} — the closing push and the mid-round push — so {@code adapter.git} runs no
     * push argv of its own; a second one appearing here is the duplication this guard exists for.
     */
    def "M3: adapter.git builds no push argv — the transfer owner's GitPush is the one construction site"() {
        expect:
        pushInvocations() == []
        Files.readString(PUSH_OWNER).contains('argv.add("push")')
    }

    // FR7, D6: no git push may ever force — the non-fast-forward fence must stand.
    // Scenario: git/git-task-persistence / Non-fast-forward never escalates to force
    // Scenario: git/git-task-persistence / Push names exactly the task branch
    // Scenario: git/git-task-persistence / The CAS reset is the only non-fast-forward write
    // Scenario: lifecycle/task-branch-contract / Origin history is never rewritten
    def "no git push the factory builds uses a forcing flag or forced refspec"() {
        given:
        def owner = Files.readString(PUSH_OWNER)

        expect:
        !owner.contains('--force')
        !owner.contains('"-f"')
        !(owner =~ /"\+/)
        !owner.contains('"+" +')
    }
}
