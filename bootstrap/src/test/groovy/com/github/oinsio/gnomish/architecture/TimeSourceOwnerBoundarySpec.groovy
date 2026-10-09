package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * FR18, FR21 and design D17, D20, D22 of supervise-daemon-loops-and-embed-dashboard: real time
 * enters a running factory in one composition-root file, and the time-built slot policies have one
 * producer each (the terminal-write retry and the abort handler are derived from the slot's own
 * time equipment, task 3.9). The types already close most of the escape hatches — no project clock
 * port to adapt, no {@code ThreadSleeper} visible outside {@code :bootstrap}, no {@code system()}
 * factory to call — but {@code InstantSource.system()}, {@code Clock.systemUTC()} and {@code
 * Instant.now()} are JDK statics no type can hide, so a whole-tree text scan is the gate (the
 * {@link BaseHeadDefaultBoundarySpec} shape, in the one module that sees every layer).
 *
 * <p>Scope: every module's {@code src/main} except three that are not the factory's production.
 * {@code test-fixtures} is test code, held to the same literal set by {@code
 * checkTestTimeInjection}, which honours the in-place {@code real-time-wiring:} justification;
 * {@code build-logic} and {@code build-checks} are the build's own code and never run inside the
 * factory process. Comments are stripped, so prose naming a forbidden call is not a violation.
 *
 * <p>Each allowlist is exact in both directions — nothing outside it spells a literal, and every
 * listed file still spells exactly what it is listed for — so a moved owner fails here instead of
 * leaving a dead entry behind.
 *
 * <p>Kept in sync with {@code TestTimeInjectionCheck} in {@code build-logic} (no shared classpath:
 * the build cannot load a test class): both hold the same real-time literal set, the production
 * side here and the test side there; the identity feature below reads the check's source and fails
 * when the two sets differ.
 */
class TimeSourceOwnerBoundarySpec extends Specification {

    private static final String ROOT = 'bootstrap/src/main/java/com/github/oinsio/gnomish/app/'

    /**
     * The real-time literals (D17, D20): display name → detector. A name omits the call's opening
     * parenthesis, and every seeded source below is split at it: this file is itself a test source,
     * and the test-time gate would otherwise read the seed text as a real clock.
     */
    static final Map<String, Pattern> REAL_TIME = [
        'Clock.systemUTC': ~/\bClock\.systemUTC\s*\(/,
        'Clock.systemDefaultZone': ~/\bClock\.systemDefaultZone\s*\(/,
        'InstantSource.system': ~/\bInstantSource\.system\s*\(/,
        // The dot escaped: a bare "Instant now(" would match ObservabilityWiring's declaration.
        'Instant.now': ~/\bInstant\.now\s*\(/,
        'new SystemClock': ~/\bnew\s+SystemClock\s*\(/,
        'new ThreadSleeper': ~/\bnew\s+ThreadSleeper\s*\(/,
        '.system': ~/\.system\s*\(/,
    ]

    /** The files allowed to spell a real-time literal, each with exactly the literals it spells. */
    private static final Map<String, Set<String>> REAL_TIME_ALLOWED = [
        // D20: the one line of real time in the process — the time equipment bean.
        (ROOT + 'ManualRunConfiguration.java'): [
            'InstantSource.system',
            'new ThreadSleeper',
            '.system'
        ] as Set,
        // Not time: the egress allowlist's DNS resolver, HostResolver.system() (design D17).
        'adapters/src/main/java/com/github/oinsio/gnomish/adapter/check/http/EgressAllowlist.java': ['.system'] as Set,
    ]

    /** One-producer constructions (D22): literal → the one production file allowed to spell it. */
    private static final Map<String, String> ONE_PRODUCER = [
        // A second producer of the time-built retry is the defect task 3.6 removed; task 3.9 moved
        // the one producer into the wiring, which derives it from the slot's own time equipment.
        'new TerminalWriteRetry(': 'application/src/main/java/com/github/oinsio/gnomish/app/SlotWiring.java',
        // Task 3.9: the slot's abort handler is built over its assembly's clock in exactly one place.
        'new AbortHandler(': 'application/src/main/java/com/github/oinsio/gnomish/app/SlotWiringFactory.java',
        'new TakeOutcomeDispatch(': 'application/src/main/java/com/github/oinsio/gnomish/app/SlotWiring.java',
    ]

    /** The factory's production sources, comments stripped later; see the class javadoc for the three exclusions. */
    static List<File> factorySources() {
        RepoSourceTree.productionSources { String path ->
            !(path.startsWith('test-fixtures/') || path.startsWith('build-logic/') || path.startsWith('build-checks/'))
        }
    }

    /** The literals of {@link #REAL_TIME} this source spells in code, outside comments. */
    static Set<String> realTimeLiterals(String source) {
        def code = source.readLines().collect {
            RepoSourceTree.codeOnly(it)
        }.join('\n')
        REAL_TIME.findAll { name, pattern ->
            pattern.matcher(code).find()
        }.keySet()
    }

    // FR18, D17, D20: real time spelled anywhere but the root's one file is a hidden clock or sleeper.
    def "FR18: real time is constructed only in the composition root's one file"() {
        given: 'every factory production source'
        def sources = factorySources()

        expect: 'the scan really reached the tree'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        when:
        Map<String, Set<String>> spelling = sources.collectEntries { file ->
            [(RepoSourceTree.relative(file)): realTimeLiterals(RepoSourceTree.code(file))]
        }.findAll { path, literals -> !literals.isEmpty() }

        then: 'exactly the allowlisted files spell real time, each exactly what it is listed for'
        spelling == REAL_TIME_ALLOWED
    }

    // FR18, D22: a second construction site of a root-built policy is a second owner.
    def "FR18, D22: #literal is spelled only in its one producer"() {
        given:
        def sources = factorySources()

        expect: 'the scan really reached the tree'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        when:
        def producers = sources.findAll {
            RepoSourceTree.code(it).contains(literal)
        }
        .collect { RepoSourceTree.relative(it) }

        then: 'nothing else constructs it, and the allowlisted producer still does'
        producers == [ONE_PRODUCER[literal]]

        where:
        literal << ONE_PRODUCER.keySet().toList()
    }

    // D22: the probe seam is ContainerRuntimeProbe, the TTY seam TerminalPresence; a JDK functional
    //     type on an application or root signature is a seam with no name a spec can fake by role.
    def "D22: no BooleanSupplier in application or the composition root"() {
        given: 'the two modules the ban covers'
        def sources = RepoSourceTree.productionSources { String path ->
            path.startsWith('application/src/main/') || path.startsWith('bootstrap/src/main/')
        }
        def scanned = sources.collect { RepoSourceTree.relative(it) }

        expect: 'the scan reached both modules, including the ban\'s one former holder'
        scanned.contains('application/src/main/java/com/github/oinsio/gnomish/app/ConsoleTakeoverConfirmation.java')
        scanned.contains(ROOT + 'ManualRunConfiguration.java')

        and: 'the allowlist is empty: no file spells the type in code'
        sources.findAll {
            RepoSourceTree.code(it).contains('BooleanSupplier')
        }.isEmpty()
    }

    // FR21: the test-side gate bans what this one bans — a literal added to one set only would let
    //     the same hidden clock through on the other side.
    def "FR21: the test-time gate holds the same real-time literal set"() {
        given: 'the check\'s own source, its literal block'
        def check = RepoSourceTree.repoRoot().resolve(
                'build-logic/src/main/groovy/com/github/oinsio/gnomish/build/TestTimeInjectionCheck.groovy').toFile().text
        def block = check.substring(check.indexOf('REAL_TIME = ['), check.indexOf(']', check.indexOf('REAL_TIME = [')))

        when:
        def checkPatterns = (block =~ /~\/(.+)\/,/).collect {
            it[1] as String
        } as Set

        then:
        checkPatterns == REAL_TIME.values()*.pattern() as Set
    }

    // Over a clean tree the scans find nothing but the owners, so only seeded sources tell a
    //     working detector from a broken one; a declaration or a comment must not count.
    def "the detector finds a seeded real-time read and leaves look-alikes alone: #shape"() {
        expect:
        realTimeLiterals(source) == expected as Set

        where:
        shape | source || expected
        'a direct read' | 'var at = Instant.now' + '();' || ['Instant.now']
        'the method declaration' | 'public Instant now() { return clock.instant(); }' || []
        'the system clock' | 'this.clock = Clock.systemUTC' + '();' || ['Clock.systemUTC']
        'the system zone clock' | 'var c = Clock.systemDefaultZone' + '();' || ['Clock.systemDefaultZone']
        'the JDK source' | 'return InstantSource.system' + '();' || [
            'InstantSource.system',
            '.system'
        ]
        'the real sleeper' | 'var s = new ThreadSleeper' + '();' || ['new ThreadSleeper']
        'the deleted adapter' | 'var c = new SystemClock' + '();' || ['new SystemClock']
        'a system() factory' | 'var r = TerminalWriteRetry.system' + '();' || ['.system']
        'a javadoc mention' | ' * never {@code Instant.now' + '()} here' || []
        'a trailing comment' | 'var at = clock.instant(); // not Instant.now' + '()' || []
    }
}
