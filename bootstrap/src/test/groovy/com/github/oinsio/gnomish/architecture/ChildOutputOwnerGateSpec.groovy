package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * FR1, FR5, FR8 and design D1, D2, D8 of make-pipe-reads-non-parking: a child's output pipe is
 * obtained and read in one file, {@code ChildOutput} of {@code :subprocess}, which never parks in a
 * read; and real time is slept on in production in {@code ThreadSleeper} only (ADR 0014) — the owner's
 * tick is a bounded wait for the child's exit, not a sleep (D2 as amended 2026-10-19). Split from {@code DrainOwnerGateSpec}, which keeps the join (M12) and the
 * cut flag (M16): this spec owns the pipe, that one the drain.
 *
 * <p>Three detectors, in call shape, over every production source {@code RepoSourceTree} walks
 * ({@code :test-fixtures}' {@code src/main} included), comments stripped:
 * <ul>
 *   <li><b>obtain</b>: {@code .getInputStream(}/{@code .getErrorStream(} and Groovy's
 *       {@code .inputStream}/{@code .errorStream} — the forms {@code RawCaptureGateSpec.PROCESS_STREAM}
 *       recognizes, so a method <em>definition</em> ({@code InputStream getInputStream()} of {@code
 *       FakeProcess}) is not a hit. {@code FakeProcess} and {@code ScriptedSandboxDocker}, which D8
 *       named as definitions, match no detector today and are therefore not listed.</li>
 *   <li><b>read</b>: a read on a child's raw stream — a {@code read…}/{@code transferTo}/{@code text}
 *       call chained on the accessor, a JDK {@code inputReader(}/{@code errorReader(}, or the accessor
 *       wrapped in a reader. The stream <em>view</em> of a {@code ChildOutput} ({@code
 *       output.stream()}) is deliberately not a hit: it is the owner's pull form (D1), whose
 *       {@code read} runs the non-parking steps, and {@code StreamDrain} and {@code
 *       TaskExecutionEnvironmentContract.readFully} read it legitimately. A raw stream can reach a
 *       reader only through the accessor, so the read is judged where the accessor is.</li>
 *   <li><b>sleep</b>: {@code Thread.sleep(}.</li>
 * </ul>
 *
 * <p>The five {@link #EXEMPTIONS} are test-time sites of {@code :test-fixtures} (D8 as amended
 * 2026-10-19), each asserted to still match every detector it is listed for. None is on a runtime
 * path: a parked read there can hang a spec, never the factory. <b>Revisit</b> the list by
 * {@code /architect} on any of: a spec hangs or flakes on a parked read in one of these fixtures; a
 * sixth fixture reader or sleep is proposed; one of these fixtures is used outside the test
 * classpath; {@code ChildOutput} gains a line read or a capture-to-end form for a production reader
 * (then the four readers move onto it and their rows leave).
 */
class ChildOutputOwnerGateSpec extends Specification {

    private static final String RAW = /\.(?:get(?:Input|Error)Stream\s*\(\s*\)|(?:input|error)Stream\b)/

    static final Map<String, Pattern> DETECTORS = [
        obtain: ~/\.get(?:Input|Error)Stream\s*\(|\.(?:input|error)Stream\b/,
        read: Pattern.compile(RAW + /\s*\.\s*(?:read\w*|transferTo|getText|text|withReader|withStream|eachLine|newReader)\b/
        + /|\.(?:input|error)Reader\s*\(/
        + /|\b(?:InputStreamReader|BufferedInputStream|DataInputStream)\s*\(\s*[\w.]*/ + RAW),
        sleep: ~/\bThread\s*\.\s*sleep\s*\(/,
    ]

    private static final String OWNER = 'subprocess/src/main/java/com/github/oinsio/gnomish/subprocess/ChildOutput.java'
    private static final String SLEEPER = 'bootstrap/src/main/java/com/github/oinsio/gnomish/app/ThreadSleeper.java'
    private static final String FIXTURES = 'test-fixtures/src/main/groovy/com/github/oinsio/gnomish/'

    /** Relative path -> the detectors it is exempt from, and why. */
    static final Map<String, Map> EXEMPTIONS = [
        (FIXTURES + 'gitobjects/LocalGitRepoFixture.groovy'): [detectors: ['obtain', 'read'],
            reason: 'reads to end of stream a git process it launched itself, at test time'],
        (FIXTURES + 'adapter/git/SeedTransferFixture.groovy'): [detectors: ['obtain', 'read'],
            reason: 'reads to end of stream a git process it launched itself, at test time'],
        (FIXTURES + 'sandbox/environment/GuardImageAvailability.groovy'): [detectors: ['obtain', 'read'],
            reason: 'reads to end of stream a docker process it launched itself, at test time'],
        (FIXTURES + 'sandbox/environment/GroupLeader.groovy'): [detectors: ['obtain', 'read'],
            reason: 'one readiness line of a live stand-in, which a drain waiting for the end cannot give'],
        (FIXTURES + 'adapter/git/StallingGit.groovy'): [detectors: ['sleep'],
            reason: 'awaitStall polls a stand-in\'s log every 20 ms, bounded by a 20 s deadline'],
    ]

    // Scenario: lifecycle/subprocess-supervision / Only the owner touches a child's output stream
    def "no production source outside the owner obtains, reads or sleeps on a child's stream"() {
        given:
        def sources = RepoSourceTree.productionSources()
        assert sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        def hits = sources.collectEntries { file ->
            def code = RepoSourceTree.code(file)
            [(RepoSourceTree.relative(file)): DETECTORS.findAll {
                    it.value.matcher(code).find()
                }.keySet()]
        }.findAll { it.value }
        def scanned = sources.collect { RepoSourceTree.relative(it) } as Set

        expect: 'the scan reached the owner, the sleeper, every reader and every exemption'
        scanned.containsAll([OWNER, SLEEPER] + DrainOwnerGateSpec.READERS.keySet() + EXEMPTIONS.keySet())

        and: 'every hit is the owner obtaining, the sleeper sleeping, or a listed exemption'
        hits.collectMany { path, found ->
            found.findAll { !permitted(path, it) }.collect { "$path: $it" }
        } == []

        and: 'every permitted entry still matches what it is permitted, so the list cannot go stale'
        hits[OWNER] == ['obtain'] as Set
        hits[SLEEPER] == ['sleep'] as Set
        EXEMPTIONS.findAll { path, entry ->
            hits[path] != (entry.detectors as Set)
        }.keySet() == [] as Set
    }

    // Scenario: lifecycle/subprocess-supervision / A drain cannot be started on a bare stream
    def "every drain is started from a ChildOutput and no bare-stream overload survives"() {
        given:
        def childOutput = Class.forName('com.github.oinsio.gnomish.subprocess.ChildOutput')
        def starts = [
            'com.github.oinsio.gnomish.subprocess.Drain': ['start', 'capped', 'into'],
            'com.github.oinsio.gnomish.adapter.agent.StreamDrain': ['start'],
            'com.github.oinsio.gnomish.adapter.check.BoundedTail': ['start'],
        ].collectMany { owner, names ->
            Class.forName(owner).declaredMethods.findAll { it.name in names }
        }

        expect: 'each named start exists, takes the owner\'s value first, and none takes a stream'
        starts*.name.toSet() == ['start', 'capped', 'into'] as Set
        starts.every { it.parameterTypes[0] == childOutput }
        starts.findAll { m ->
            m.parameterTypes.any {
                InputStream.isAssignableFrom(it)
            }
        } == []

        and: 'a handle hands out the owner\'s value, never a stream'
        Class.forName('com.github.oinsio.gnomish.sandbox.ExecHandle').getMethod('output').returnType == childOutput
    }

    def "the detectors: #shape"() {
        expect:
        DETECTORS[detector].matcher(RepoSourceTree.stripComments(source)).find() == detected

        where:
        shape | detector | source || detected
        'a getter call' | 'obtain' | 'process.getInputStream()' || true
        'a Groovy property' | 'obtain' | 'process.errorStream.text' || true
        'a definition' | 'obtain' | 'InputStream getInputStream() {' || false
        'a commented call' | 'obtain' | '// process.getErrorStream()' || false
        'a chained read' | 'read' | 'process.getInputStream().read(buffer)' || true
        'a Groovy text read' | 'read' | 'process.inputStream.getText(\'UTF-8\')' || true
        'a JDK reader' | 'read' | 'process.inputReader()' || true
        'a wrapped stream' | 'read' | 'new InputStreamReader(leader.inputStream)' || true
        'the owner\'s view' | 'read' | 'output.stream().readAllBytes()' || false
        'a file read' | 'read' | 'Files.readAllBytes(path)' || false
        'a sleep' | 'sleep' | 'Thread.sleep(1)' || true
        'a sleeper port' | 'sleep' | 'sleeper.sleep(bound)' || false
    }

    private static boolean permitted(String path, String detector) {
        (path == OWNER && detector != 'sleep') || (path == SLEEPER && detector == 'sleep') || detector in (EXEMPTIONS[path]?.detectors ?: [])
    }
}
