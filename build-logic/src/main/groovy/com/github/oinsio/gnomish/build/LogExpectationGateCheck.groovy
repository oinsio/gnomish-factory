package com.github.oinsio.gnomish.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build when a module's test run emitted an operator log event whose code no capture
 * anywhere in that run was watching (FR17, design D16 of harden-logging-observability).
 *
 * <p><b>The defect this exists to catch.</b> A degrade path is written, it logs a WARN nobody ever
 * asserts, and nothing goes red — the line exists, renders plausibly, and is discovered only in a
 * post-mortem where its absence would have mattered. The static gate ({@code LogContractGateSpec})
 * asks whether a catalog code is <em>named</em> by some test source, which a code in a comment
 * satisfies; this task asks the behavioral question the static one cannot: was the line actually
 * observed while it was actually emitted.
 *
 * <p><b>Why by code rather than per feature.</b> The runtime gate watches every feature, and a
 * per-feature verdict makes an offender of every behavior spec that merely crosses an
 * already-pinned degrade path — measured on this tree, 162 specs and 667 features, almost all for
 * lines a sibling feature pins properly. Making each of them carry an allowance would trade a real
 * signal for 162 boilerplate reasons and take whole specs out from under the gate. So the failure
 * is by code, over the whole run; the per-feature detail stays in {@code features-*.txt} beside
 * this task's own report, because a line emitted in silence is what the change exists to end.
 *
 * <p><b>How to satisfy it.</b> Pin the line — a spec asserting the event through {@code
 * LogCaptureSupport} (or the hand-rolled {@code ListAppender} block that predates it) marks its
 * code watched for the whole run. Where a spec must traverse a path it deliberately does not pin,
 * {@code @AllowsUnexpectedLogEvents} with a reason keeps its events out of the verdict.
 */
abstract class LogExpectationGateCheck extends DefaultTask {

    /** The observation files the Spock extension wrote, one per test JVM. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getObservations()

    /** The verdict, written whether or not it fails, so a green run still leaves the evidence. */
    @OutputFile
    abstract RegularFileProperty getReport()

    @TaskAction
    void check() {
        Set<String> watched = []
        Map<String, List<String>> unwatched = [:]
        observations.files.findAll { it.isFile() }.sort { it.path }.each { file ->
            file.readLines().each { line -> classify(line, watched, unwatched) }
        }
        def failing = unwatched.keySet().findAll { !watched.contains(it) }.sort()
        def text = render(watched, unwatched, failing)
        report.get().asFile.tap { it.parentFile.mkdirs() }.text = text
        if (!failing.isEmpty()) {
            throw new GradleException("${failing.size()} operator log event code(s) were emitted " +
            "during ${path} with no capture watching them: ${failing.join(', ')}. " +
            "See ${report.get().asFile}.")
        }
    }

    /**
     * Package-private, not private: it is called from inside a closure, and Groovy resolves that
     * call dynamically — a private member is invisible to it.
     */
    static void classify(String line, Set<String> watched, Map<String, List<String>> unwatched) {
        def parts = line.split('\t', -1)
        if (parts.length < 2) {
            return
        }
        // `allowed` rows are recorded for the report only: an allowance is a statement about one
        // spec's traversal, never a licence for the code everywhere else in the run.
        if (parts[0] == 'watched') {
            watched.add(parts[1])
        } else if (parts[0] == 'unwatched' && parts[1] != '-') {
            unwatched.computeIfAbsent(parts[1]) { [] }.add(line)
        }
    }

    String render(Set<String> watched, Map<String, List<String>> unwatched, List<String> failing) {
        def lines = ["Log-expectation gate — ${path}", '']
        lines << "Codes watched by some capture: ${watched.size()}"
        lines << "Codes emitted with nothing watching: ${unwatched.size()}"
        lines << "Failing (emitted, never watched anywhere in this run): ${failing.size()}"
        failing.each { code ->
            lines << ''
            lines << code
            unwatched[code].take(5).each { lines << "  $it" }
        }
        lines.join('\n') + '\n'
    }
}
