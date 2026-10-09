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
 * that wires the real {@code ThreadSleeper} and {@code InstantSource.system()} with the production bound — for
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
 * <p><b>What it looks for.</b> Every way a source builds real time — the same literal set the
 * production gate {@code TimeSourceOwnerBoundarySpec} bans outside the composition root (design
 * D17, D20 of supervise-daemon-loops-and-embed-dashboard): {@code Clock.systemUTC(}, {@code
 * Clock.systemDefaultZone(}, {@code InstantSource.system(}, {@code Instant.now(}, {@code new
 * SystemClock(}, {@code new ThreadSleeper(}, and any call of the shape {@code .system(...)}, with
 * or without arguments. The last is this codebase's naming convention for "production wiring, real
 * clock" ({@code RemoteOutageGate.system(baseRefGit, cloneDir, idleInterval)} was one), so a
 * component that adopts the convention tomorrow is covered the day it is written. A real clock is
 * the same latent defect as a real sleeper: a spec stamping or measuring on the wall clock depends
 * on when it runs, and two components on two clocks disagree in a way no assertion pins. Comment
 * lines are skipped, so prose about a factory is not a violation.
 *
 * <p><b>How to satisfy it.</b> Preferably by construction: build the component with virtual time
 * ({@code VirtualTimeEquipment} or {@code VirtualClock} in {@code :test-fixtures}), which keeps the
 * production bound and makes it elapse instantly. Where the call really is right — a fixture that
 * assembles the shipped composition, a real daemon or remote that must really be waited for, a
 * resolver with no time in it at all — put {@link #MARKER} and the reason on the same line or the
 * line above. The justification then lives beside the call rather than in a list somewhere else,
 * the same shape {@code @DoNotMutate} uses for the mutation gate.
 *
 * <p><b>A marker that excuses nothing fails too.</b> A {@code // real-time-wiring:} comment whose
 * line — or, for a comment run, the first code line below it — builds no real time is left over
 * from a call that moved or changed, and it would silently excuse whatever real time is written
 * there next. So the list of markers stays the list of real exceptions, the way an exemption
 * annotation that would pass without itself is reported by the parameter-count gate.
 *
 * <p>Kept in sync with {@code TimeSourceOwnerBoundarySpec} in {@code :bootstrap} (no shared
 * classpath: the build cannot load a test class): both hold the same real-time literal set, the
 * test side here and the production side there; that spec reads {@link #REAL_TIME} from this
 * file's source and fails when the two sets differ.
 */
@CacheableTask
abstract class TestTimeInjectionCheck extends DefaultTask {

    /** The comment marker that excuses one call, followed by the reason it is excused. */
    static final String MARKER = 'real-time-wiring:'

    /** A marker in use: a line comment carrying it, not a mention of it in javadoc prose. */
    static final Pattern MARKER_USE = ~/\/\/\s*real-time-wiring:/

    /**
     * The real-time literals, kept equal to {@code TimeSourceOwnerBoundarySpec}'s set (FR21 of
     * supervise-daemon-loops-and-embed-dashboard). The {@code .system(} arity is deliberately
     * unconstrained: the wiring a {@code system()} factory performs is the same whether or not it
     * takes arguments, and requiring an empty argument list once let that whole class through
     * unseen. {@code Instant.now(} keeps its dot escaped, so a declaration {@code Instant now()}
     * is not a read.
     */
    static final List<Pattern> REAL_TIME = [
        ~/\bClock\.systemUTC\s*\(/,
        ~/\bClock\.systemDefaultZone\s*\(/,
        ~/\bInstantSource\.system\s*\(/,
        ~/\bInstant\.now\s*\(/,
        ~/\bnew\s+SystemClock\s*\(/,
        ~/\bnew\s+ThreadSleeper\s*\(/,
        // Every X.system(...) factory, InstantSource.system( included.
        ~/\.system\s*\(/,
    ]

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
                if (MARKER_USE.matcher(line).find() && !excusesRealTime(lines, index)) {
                    violations << "${file.path}:${index + 1}: marker excuses nothing: ${line.trim()}".toString()
                }
                if (isComment(line) || !buildsRealTime(line)) {
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
Test sources build real time instead of injecting it (${violations.size()} call(s)):

${violations.join('\n')}

Real time in a spec is a latent defect, not a bug you can see: a real sleeper blocks for the whole
production bound once some collaborator starts reporting the failure the retry waits on, and a
real clock makes stamps and intervals depend on when the spec runs.

Build the component with virtual time instead — VirtualTimeEquipment or VirtualClock in
:test-fixtures — which keeps the production bound and elapses it instantly. If the call really is
right (a fixture assembling the shipped composition, a real daemon or remote that must really be
waited for, no time involved at all), write `// ${MARKER} <reason>` on that line or the line above.
A marker that excuses nothing (its call moved or no longer builds real time) is deleted.""")
        }
    }

    static boolean buildsRealTime(String line) {
        REAL_TIME.any { it.matcher(line).find() }
    }

    static boolean isComment(String line) {
        String trimmed = line.trim()
        trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('/*')
    }

    /**
     * Whether the marker at {@code index} covers a real-time call: its own line when it trails code,
     * else the first code line below its comment run — the line {@link #excusedAbove} would excuse.
     */
    static boolean excusesRealTime(List<String> lines, int index) {
        int target = index
        while (target < lines.size() && isComment(lines[target])) {
            target++
        }
        target < lines.size() && buildsRealTime(lines[target])
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
