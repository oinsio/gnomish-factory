package com.github.oinsio.gnomish.build

import java.util.regex.Pattern
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build when a test source wires production real time instead of injecting it.
 *
 * <p><b>The defect this exists to catch.</b> Components that retry or poll take their {@code
 * Sleeper} and {@code Clock} as constructor arguments precisely so a spec can drive them on virtual
 * time. Beside each such component the codebase also offers a no-argument {@code system()} factory
 * that wires the real {@code ThreadSleeper} and {@code SystemClock} with the production bound — for
 * the composition root to call. When a spec calls it instead, nothing goes red: the collaborator in
 * that spec never reports the failure that would make the retry sleep, so the call sits there
 * looking correct. It stays correct only until some later change makes that collaborator report an
 * outage, and then the spec does not fail — it blocks, for the production bound, once per exercise
 * of the path. Under PIT that is the "mutant hangs on real I/O instead of failing fast" mode
 * {@code .claude/rules/testing.md} already records as having stalled a minion in this build.
 *
 * <p>Because the failure is latent, code review is the wrong instrument: there is nothing red to
 * notice, and the reviewer has to reason about a state the spec does not currently reach. So the
 * build asks instead, at the one moment the question is cheap — when the call is written.
 *
 * <p><b>What it looks for.</b> A call of the shape {@code SomeType.system()} in a test source. That
 * shape is this codebase's naming convention for "production wiring, real clock" ({@code
 * TerminalWriteRetry.system()}, {@code GitInfrastructureRetry.system()}), so the rule needs no list
 * of type names to keep in step — a component that adopts the convention tomorrow is covered the
 * day it is written. Comment lines are skipped, so prose about a factory is not a violation.
 *
 * <p><b>How to satisfy it.</b> Preferably by construction: build the component with virtual time
 * ({@code VirtualTimeRetries} in {@code :test-fixtures}, or {@code VirtualClock}/{@code
 * VirtualSleeper} directly), which keeps the production bound and makes it elapse instantly. Where
 * the call really is right — a spec asserting the production defaults themselves, a resolver with
 * no time in it at all — put {@link #MARKER} and the reason on the same line or the line above.
 * The justification then lives beside the call rather than in a list somewhere else, the same shape
 * {@code @DoNotMutate} uses for the mutation gate.
 */
@CacheableTask
abstract class TestTimeInjectionCheck extends DefaultTask {

    /** The comment marker that excuses one call, followed by the reason it is excused. */
    static final String MARKER = 'real-time-wiring:'

    private static final Pattern SYSTEM_FACTORY = ~/\b[A-Z][A-Za-z0-9_]*\.system\s*\(\s*\)/

    /** Test sources to scan; Groovy and Java alike. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getTestSources()

    /** Written on success so the task is up-to-date without rescanning. */
    @OutputFile
    abstract RegularFileProperty getReport()

    @TaskAction
    void check() {
        List<String> violations = []
        testSources.files.findAll { it.isFile() }.sort { it.path }.each { file ->
            List<String> lines = file.readLines()
            lines.eachWithIndex { String line, int index ->
                if (isComment(line) || !SYSTEM_FACTORY.matcher(line).find()) {
                    return
                }
                if (excused(line) || excusedAbove(lines, index)) {
                    return
                }
                violations << "${file.path}:${index + 1}: ${line.trim()}".toString()
            }
        }
        report.get().asFile.tap { it.parentFile.mkdirs() }.text = violations.join('\n')
        if (!violations.isEmpty()) {
            throw new GradleException("""\
Test sources wire production real time instead of injecting it (${violations.size()} call(s)):

${violations.join('\n')}

A `.system()` factory wires the real ThreadSleeper/SystemClock with the production bound. In a
spec that is a latent hang, not a bug you can see: it sleeps only once some collaborator starts
reporting the failure the retry waits on, and then it blocks for the whole bound per exercise.

Build the component with virtual time instead — VirtualTimeRetries in :test-fixtures, or
VirtualClock/VirtualSleeper directly — which keeps the production bound and elapses it instantly.
If the call really is right (asserting production defaults, no time involved), write
`// ${MARKER} <reason>` on that line or the line above.""")
        }
    }

    static boolean isComment(String line) {
        String trimmed = line.trim()
        trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('/*')
    }

    static boolean excused(String line) {
        line.contains(MARKER)
    }

    /**
     * Whether the contiguous run of comment lines directly above {@code index} carries the marker.
     * The whole run, not just the line before: a justification worth writing is often two lines
     * long, and a rule that only looked one line up would reject the second half of its own advice.
     */
    static boolean excusedAbove(List<String> lines, int index) {
        for (int above = index - 1; above >= 0 && isComment(lines[above]); above--) {
            if (excused(lines[above])) {
                return true
            }
        }
        false
    }
}
