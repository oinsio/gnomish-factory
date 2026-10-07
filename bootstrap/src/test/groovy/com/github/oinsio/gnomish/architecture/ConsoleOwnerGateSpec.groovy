package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * Single-owner table row 2 of harden-untrusted-text-sinks: every byte the factory writes to the
 * operator's terminal outside the logger leaves through one class, {@code SystemConsoleIO}. A
 * command that reaches for {@code System.out}/{@code System.err} itself bypasses the split between
 * the human path (which renders escape sequences visibly) and the machine path (which does not),
 * and nothing else in the build would notice: the write compiles, the output looks right in a
 * spec, and the neutralization is simply absent for that one site.
 *
 * <p>A source scan rather than a type rule, for the reason every gate in this package is one: a
 * static field access on {@code System} is not a dependency ArchUnit's module rules look at, and
 * the offending line is otherwise ordinary Java. The allowlist is the owner alone — an entry added
 * here is a decision to have a second writer, which the design says there is not.
 *
 * <p>The input half is gated here too (design D7 of make-run-headless, third single-owner row):
 * {@code run} takes its operator decision from {@code --decision} alone, so the one production
 * console read left is the takeover confirmation's {@code [y/N]}.
 *
 * <p>FR6, M2 of harden-untrusted-text-sinks; FR3, M1 of make-run-headless.
 */
class ConsoleOwnerGateSpec extends Specification {

    /** The console owner: the one production class allowed to name a process stream. */
    private static final String OWNER = 'application/src/main/java/com/github/oinsio/gnomish/app/console/SystemConsoleIO.java'

    /**
     * The shared fixture module is not production code — it never lands on a production classpath
     * (the {@code layering} block of every module's build file keeps it off), and its one console,
     * {@code LiveConsoleIO}, exists precisely to bind the owner to whatever stream a spec has
     * swapped in. Scanning it would make the gate fail on the fixture that proves the owner works.
     */
    private static final String FIXTURE_TREE = 'test-fixtures/src/main/'

    /**
     * Every production file allowed to spell {@code readLine(}, with its reason. Every {@code
     * readLine(} is matched whatever its receiver, rather than only calls on a {@code ConsoleIO}:
     * a text scan cannot know a receiver's type, and a console read through a variable named
     * {@code in} would pass a receiver-shaped pattern. A new subprocess reader therefore costs one
     * entry here — the price of a gate that a renamed variable cannot slip past. The name is
     * matched, not the call, so a method reference ({@code io::readLine}) is no way around it.
     */
    private static final Map<String, String> READ_LINE_SITES = [
        (OWNER) : 'the console owner: implements the port over stdin',
        'application/src/main/java/com/github/oinsio/gnomish/app/port/console/ConsoleIO.java' : 'the port: declares readLine, calls nothing',
        'application/src/main/java/com/github/oinsio/gnomish/app/ConsoleTakeoverConfirmation.java' : 'the one console read: the takeover [y/N] confirmation',
        'adapters/src/main/java/com/github/oinsio/gnomish/adapter/check/BoundedTail.java' : 'a BufferedReader over a command check subprocess, not the console',
        'adapters/agent/src/main/java/com/github/oinsio/gnomish/adapter/agent/StreamJsonParser.java' : 'a BufferedReader over the agent CLI stdout, not the console',
    ]

    /** The allowlisted files that read a subprocess, not the operator: none may name the console port. */
    private static final List<String> SUBPROCESS_READERS = READ_LINE_SITES.keySet().findAll {
        it.startsWith('adapters/')
    }.toList()

    private static final Pattern READ_LINE = Pattern.compile(/\breadLine\b/)

    /** Any write to a process stream: {@code print}, {@code println}, {@code printf}, {@code write}. */
    private static final Pattern DIRECT_WRITE = Pattern.compile(
    /System\s*\.\s*(?:out|err)\s*\.\s*(?:print|println|printf|format|write|append)\s*\(/)

    // FR6, M2: the owner is the only survivor of the sweep over production sources
    def "no production class outside the console owner writes to a process stream"() {
        given: 'every production source in the build but the shared fixtures, comments removed'
        def sources = RepoSourceTree.productionSources {
            !it.startsWith(FIXTURE_TREE)
        }

        expect: 'the scan really reached the tree — a mis-resolved root would pass silently'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        when:
        def violations = sources.findAll {
            RepoSourceTree.relative(it) != OWNER && writesDirectly(RepoSourceTree.code(it))
        }.collect {
            RepoSourceTree.relative(it)
        }

        then: 'the gate names every offending file'
        violations == []
    }

    // The owner really is in the scanned tree: a gate whose allowlist names a path that does not
    // exist would pass forever, and the rename that broke it would be invisible
    def "the allowlisted owner exists and holds both write paths"() {
        given:
        def owner = RepoSourceTree.productionSources {
            it == OWNER
        }

        expect: 'the allowlist names a real file'
        owner.size() == 1

        and: 'and that file is the one holding the human/machine split the gate protects'
        def code = RepoSourceTree.code(owner.first())
        code.contains('void print(String text)')
        code.contains('void printMachine(String text)')
    }

    // The detector is the gate — a seeded violation must fail it, or a green run means nothing
    def "a seeded #shape is detected"() {
        expect:
        writesDirectly(seeded)

        where:
        shape | seeded
        'stdout println' | 'System.out.println("task not found: " + taskId);'
        'stderr println' | 'System.err.println(ex.getMessage());'
        'stdout print' | 'System.out.print(banner);'
        'stderr printf' | 'System.err.printf("%s%n", line);'
        'spaced out' | 'System . out . println ( x );'
        'raw stream write' | 'System.out.write(bytes, 0, bytes.length);'
    }

    // Naming the streams without writing to them — handing one to the owner, reading stdin — is
    // the composition root's job (see ManualRunConfiguration.java) and must not be flagged
    def "a non-writing mention of a process stream is not flagged: #shape"() {
        expect:
        !writesDirectly(allowed)

        where:
        shape | allowed
        'handing stdout to the owner' | 'return new SystemConsoleIO(System.in, System.out);'
        'handing stderr to the owner' | 'return new SystemConsoleIO(System.in, System.err);'
        'a javadoc-free mention' | 'writer = new PrintStream(out, true, StandardCharsets.UTF_8);'
        'the console port' | 'console.print(text + ConsoleIO.LINE_END);'
    }

    // FR3, M1 of make-run-headless: run reads no stdin — the takeover confirmation is the one
    //     console read, and every other readLine( in production is a named subprocess reader
    def "FR3, M1: readLine( appears in production only at the allowlisted sites"() {
        given: 'every production source but the shared fixtures, comments removed'
        def sources = RepoSourceTree.productionSources {
            !it.startsWith(FIXTURE_TREE)
        }

        expect: 'the scan really reached the tree'
        sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES

        when:
        def readers = sources.findAll { readsLines(RepoSourceTree.code(it)) }
        .collect { RepoSourceTree.relative(it) }
        .sort()

        then: 'exactly the allowlist — an extra file is a new console read, a missing one a stale entry'
        readers == READ_LINE_SITES.keySet().sort()

        and: 'a subprocess reader holds no console, so its entry cannot hide an operator read'
        SUBPROCESS_READERS.size() == 2
        SUBPROCESS_READERS.every {
            !RepoSourceTree.code(RepoSourceTree.repoRoot().resolve(it).toFile()).contains('ConsoleIO')
        }
    }

    def "the readLine detector: #shape"() {
        expect:
        readsLines(source) == detected

        where:
        shape | source || detected
        'a port call' | 'String answer = console.readLine();' || true
        'any receiver' | 'var line = in . readLine ( );' || true
        'a method reference' | 'Supplier<String> next = io::readLine;' || true
        'a longer name' | 'return parser.readLineage();' || false
    }

    private static boolean readsLines(String code) {
        READ_LINE.matcher(code).find()
    }

    private static boolean writesDirectly(String code) {
        DIRECT_WRITE.matcher(code).find()
    }
}
