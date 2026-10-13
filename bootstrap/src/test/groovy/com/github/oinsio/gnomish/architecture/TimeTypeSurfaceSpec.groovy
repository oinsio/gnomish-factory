package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * FR17, FR18, FR22 of supervise-daemon-loops-and-embed-dashboard: the types that carry time. The
 * JDK's {@code InstantSource} is the one type for the current instant, the real sleeper is visible
 * only to the composition root, and the instant and the sleeper travel together as one {@code
 * TimeEquipment}. Where the real-time literals are gated is {@link TimeSourceOwnerBoundarySpec}; this
 * spec gates the type surface those literals would otherwise hide behind. Scope and exclusions are
 * that spec's ({@link TimeSourceOwnerBoundarySpec#factorySources}); each detector is shown red on a
 * seeded source, since over a clean tree a broken detector and a clean tree look alike.
 */
class TimeTypeSurfaceSpec extends Specification {

    private static final String ROOT = 'bootstrap/src/main/java/com/github/oinsio/gnomish/app/'

    /** A use of {@code java.time.Clock} as a type or a static owner — never a longer name ending in it. */
    private static final Pattern CLOCK_TYPE = ~/(?<![\w.])Clock\b|\bjava\.time\.Clock\b/

    /** A project type that is itself a time source: one extending or implementing a JDK clock type. */
    private static final Pattern CLOCK_SUBTYPE = ~/\b(?:class|interface|record)\s+\w+[^{;]*\b(?:extends|implements)\b[^{;]*\b(?:InstantSource|Clock)\b/

    /** A project port for "now": an interface declaring an abstract instant read. */
    private static final Pattern NOW_PORT = ~/(?s)\binterface\s+\w+[^{]*\{[^}]*\bInstant\s+(?:now|instant)\s*\(\s*\)\s*;/

    /** Whether a source declares a project clock type of either shape. */
    static boolean declaresClockType(String code) {
        CLOCK_SUBTYPE.matcher(code).find() || NOW_PORT.matcher(code).find()
    }

    /** Whether one parameter list carries an instant source and a sleeper side by side. */
    static boolean takesThePair(String code) {
        (code =~ /\(([^()]*)\)/).any { List<String> match ->
            match[1] =~ /\bInstantSource\s+\w+/ && match[1] =~ /\bSleeper\s+\w+/
        }
    }

    private static Map<String, String> codeByPath() {
        def sources = TimeSourceOwnerBoundarySpec.factorySources()
        assert sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        sources.collectEntries {
            [(RepoSourceTree.relative(it)): RepoSourceTree.code(it)]
        }
    }

    // FR17: every time-reading component takes the JDK instant source, so one object serves all.
    // Scenario: core/time-source / A component is handed a time source
    def "FR17: no production source takes or builds java.time.Clock; InstantSource is the instant type"() {
        expect:
        codeByPath().findAll { path, code ->
            CLOCK_TYPE.matcher(code).find()
        }.keySet() == [] as Set
    }

    // FR17: a project-owned clock type, or a system adapter for one, would need a conversion at
    //     every boundary between it and the JDK type.
    // Scenario: core/time-source / No second clock type compiles
    def "FR17: no production type is a clock of its own"() {
        expect:
        codeByPath().findAll { path, code ->
            declaresClockType(code)
        }.keySet() == [] as Set
    }

    def "the clock detectors find #shape"() {
        expect:
        (CLOCK_TYPE.matcher(source).find() || declaresClockType(source)) == found

        where:
        shape | source || found
        'a Clock parameter' | 'Foo(Clock clock) {}' || true
        'a static Clock owner' | 'var c = Clock.fixed(at, zone);' || true
        'a subclass of Clock' | 'final class SystemClock extends Clock {}' || true
        'an InstantSource implementer' | 'record Fixed(Instant at) implements InstantSource {}' || true
        'a now() port' | 'public interface Clock2 {\n    Instant now();\n}' || true
        'a longer name ending in Clock' | 'final class ClaimClock { Instant tick() { return t; } }' || false
        'a now() method with a body' | 'class Wiring { Instant now() { return clock.instant(); } }' || false
    }

    // FR18: the real sleeper is built in :bootstrap, and no module depends on :bootstrap, so a
    //     class anywhere else cannot even name it. Two facts make the compiler the gate.
    // Scenario: core/time-source / A hidden sleeper does not compile
    def "FR18: the real sleeper lives in the root module, which no other module depends on"() {
        given:
        def code = codeByPath()

        expect: 'the one production Sleeper implementation is the root\'s ThreadSleeper'
        code.findAll { path, source ->
            source =~ /implements[^{]*\bSleeper\b/
        }.keySet() ==
        [ROOT + 'ThreadSleeper.java'] as Set

        and: 'no module build file depends on the root'
        def buildFiles = RepoSourceTree.repoRoot().toFile().listFiles().findAll {
            it.isDirectory()
        }.collectMany { dir ->
            [new File(dir, 'build.gradle')] + (dir.listFiles().findAll {
                it.isDirectory()
            }.collect {
                new File(it, 'build.gradle')
            })
        }.findAll { it.isFile() && !it.path.contains('/build-logic/') }
        buildFiles.size() > 15
        buildFiles.findAll { dependsOnRoot(it.text) } == []
    }

    static boolean dependsOnRoot(String buildScript) {
        buildScript =~ /project\(\s*['"]:bootstrap['"]\s*\)/
    }

    def "the root-dependency detector: #shape"() {
        expect:
        dependsOnRoot(script) == found

        where:
        shape | script || found
        'a dependency on the root' | "implementation project(':bootstrap')" || true
        'a sibling module' | "implementation project(':application')" || false
    }

    // FR22: a policy's time is the equipment its builder was handed, because no other equipment,
    //     sleeper or system default can be made outside the root.
    // F101: scenario text aligned to what this test asserts
    // Scenario: core/time-source / A policy is built from the equipment it is handed
    def "FR22: time equipment is assembled only from the root's own halves"() {
        given:
        def code = codeByPath()

        expect: 'the equipment is built in the root, and re-paired once in the heartbeat assembly'
        code.findAll { path, source ->
            source =~ /\bnew\s+TimeEquipment\s*\(/
        }.keySet() == [
            ROOT + 'ManualRunConfiguration.java',
            // The standing reaper's own sleeper over the beat's clock; TakeCommandSeams defaults it
            // to the equipment's own sleeper, so in production both halves are the root's.
            'application/src/main/java/com/github/oinsio/gnomish/app/TakeHeartbeat.java',
        ] as Set

        and: 'no factory offers a "system" default: a system() call is spelled only where it is listed'
        code.findAll { path, source ->
            '.system' in TimeSourceOwnerBoundarySpec.realTimeLiterals(source)
        }.keySet() == [
            ROOT + 'ManualRunConfiguration.java',
            'adapters/src/main/java/com/github/oinsio/gnomish/adapter/check/http/EgressAllowlist.java',
        ] as Set
    }

    // FR22: the instant source and the sleeper are one value; a signature taking both side by
    //     side is the pair the value replaced (two parameters where the value is one).
    // F101: scenario text aligned to what this test asserts
    // Scenario: core/time-source / One value replaces the pair on every signature
    def "FR22: no production signature takes an instant source and a sleeper side by side"() {
        expect: 'only the equipment\'s own record header declares the pair'
        codeByPath().findAll { path, code -> takesThePair(code) }.keySet() ==
        [
            'domain/src/main/java/com/github/oinsio/gnomish/domain/engine/time/TimeEquipment.java'
        ] as Set
    }

    def "the pair detector: #shape"() {
        expect:
        takesThePair(source) == found

        where:
        shape | source || found
        'a constructor taking both' | 'Retry(InstantSource clock, Sleeper sleeper, Duration bound) {}' || true
        'a wrapped parameter list' | 'Retry(\n        InstantSource clock,\n        Sleeper sleeper) {}' || true
        'the equipment alone' | 'Retry(TimeEquipment time, Duration bound) {}' || false
        'one half alone' | 'Wait(Sleeper sleeper, Duration interval) {}' || false
    }
}
