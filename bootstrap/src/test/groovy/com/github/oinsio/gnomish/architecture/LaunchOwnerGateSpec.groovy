package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * FR18, FR22, M13 and design D16, D19 of fix-terminal-receipt-convergence: a supervised process is
 * started by one launch owner, {@code ProcessLaunch.grouped()} in {@code :subprocess}, and nothing
 * else in production starts one or implements the role — so a plain, ungrouped launch cannot
 * reappear beside it, and every reader of a child's output can be handed a fake process instead.
 *
 * <p>The scan covers every production source outside {@code test-fixtures}, comments stripped; every
 * allowlisted file must still match, so a moved owner fails here. The allowlist is the owner alone:
 * the four start sites it replaced were routed by tasks 8.10 ({@code GitExec}), 8.12 ({@code
 * DockerCli.start}) and 8.13 ({@code HostTaskExecutionEnvironment.exec}), {@code CaptureRunner.run}
 * before them. The launcher prefix — {@code setsid}, the {@code POSIX::setsid} perl one-liner and the
 * {@code /usr/bin/perl} that runs it (task 8.19, design D19) — is spelled only in {@code
 * HostLauncher}, the owner's one helper (task 8.20 split the platform's helpers out of the owner's
 * file), so a second grouped form cannot appear beside the owner.
 *
 * <p>The owner's file holds exactly two starts (task 8.20, design D19 as amended 2026-10-17): the
 * child's, and the group sweep's {@code kill}, started ungrouped there because a grouped {@code kill}
 * would be a group to sweep in turn. A third start in the owner's file is a second launch form
 * hiding inside the allowlisted file, and fails here like a start anywhere else.
 *
 * <p>The owner is also the one place a child's standard input is touched (FR23, design D20, M13 as
 * amended): {@code getOutputStream()} and {@code redirectInput(} appear in no other production
 * source: the writers it replaced are deleted — {@code GitExecStreams.feed} by 8.10, {@code
 * ContainerFileChannel.pump} by 8.12, {@code ChildProcessStdin} by 8.13 — so the list is the owner
 * alone.
 *
 * <p>The live group registry ({@code LiveGroups}, D19 fourth amendment) is written only by the owner
 * and the group sweep, and its sweep is called only by the shutdown step, {@code RealProcessTreeKiller}.
 *
 * <p>A no-arg {@code .start()} counts as a launch in a file that names {@code ProcessBuilder} or
 * {@code ProcessLaunch}: a file that starts both a builder and a thread is flagged rather than
 * missed, and is then split or argued here.
 */
class LaunchOwnerGateSpec extends Specification {

    private static final String SUBPROCESS = 'subprocess/src/main/java/com/github/oinsio/gnomish/subprocess/'

    /** The launch owner: the one file that starts a builder and implements the role. */
    private static final String OWNER = SUBPROCESS + 'GroupedLaunch.java'

    /** The owner's one helper: the platform's launcher prefix and the sweep's {@code kill} (D19). */
    private static final String LAUNCHER = SUBPROCESS + 'HostLauncher.java'

    /** The owner's starts: the child's ({@code startOn}) and the group sweep's {@code kill} (task 8.20). */
    private static final int OWNER_STARTS = 2

    /** The files allowed to start a process, each with its reason. */
    private static final Map<String, String> STARTERS = [
        (OWNER): 'the launch owner (D16, D19)',
    ]

    /** The files allowed to touch a child's stdin, each with its reason. */
    private static final Map<String, String> STDIN_TOUCHERS = [
        (OWNER): 'the launch owner sets every child\'s stdin (D20)',
    ]

    /** A child's stdin opened for writing, or redirected by hand. */
    private static final List<Pattern> STDIN_SHAPES = [
        ~/\bgetOutputStream\s*\(\s*\)/,
        ~/\.\s*redirectInput\s*\(/,
    ]

    /** The shapes that start an OS process outright, whatever the file names. */
    private static final List<Pattern> DIRECT_STARTS = [
        ~/\bProcessBuilder\s*::\s*start\b/,
        ~/\.\s*startPipeline\s*\(/,
        ~/\bgetRuntime\s*\(\s*\)\s*\.\s*exec\s*\(/,
    ]

    /** The launcher prefix: the helper names and the one-liner's call (D19). */
    private static final List<Pattern> LAUNCHER_SHAPES = [
        ~/\bsetsid\b/,
        ~/\/usr\/bin\/perl\b/,
    ]

    /** The live group registry's second writer, and the one caller of its sweep (D19, fourth amendment). */
    private static final String SWEEP = SUBPROCESS + 'ProcessGroupSweep.java'
    private static final String SHUTDOWN_STEP =
    'application/src/main/java/com/github/oinsio/gnomish/app/serve/RealProcessTreeKiller.java'

    /** A write of the live group registry, in a file that names it; the shutdown step's sweep. */
    private static final Pattern REGISTRY_WRITE = ~/\.\s*(register|deregister)\s*\(/
    private static final Pattern REGISTRY_TYPE = ~/\bLiveGroups\b/
    private static final Pattern SWEEP_LIVE = ~/\.\s*sweepLive\s*\(/

    /** A no-arg start: a launch in a file that names the builder or the role. */
    private static final Pattern NO_ARG_START = ~/\.\s*start\s*\(\s*\)/
    private static final Pattern LAUNCH_TYPES = ~/\b(ProcessBuilder|ProcessLaunch)\b/

    /** An implementation of the role: a class clause, an anonymous class, a sub-interface. */
    private static final List<Pattern> IMPLEMENTATIONS = [
        ~/\b(implements|extends)\b[^{;]*\bProcessLaunch\b/,
        ~/\bnew\s+ProcessLaunch\s*\(/,
    ]

    // FR18, M13: a process started outside the owner is a launch no spec can fake and no sweep reaches.
    // Scenario: lifecycle/subprocess-supervision / Only the launch owner starts a process
    def "FR18, M13: a process is started only by the launch owner"() {
        expect: 'the scan reached every allowlisted file, each still starts a process, and no other file does'
        matching { starts(it) } == STARTERS.keySet().sort()
    }

    // FR22, M13, D19: a second implementation of the role is a plain form beside the grouped one.
    // Scenario: lifecycle/subprocess-supervision / The docker client of a box command is a host process
    def "FR22, M13: no production source but the owner's implements the launch role"() {
        expect:
        matching { implementsLaunch(it) } == [OWNER]
    }

    // FR23, M13, D20: a stdin touched outside the owner is a pump no thread can bound, or a pipe a
    //     descendant may hold.
    // Scenario: lifecycle/subprocess-supervision / Only the launch owner touches a child's stdin
    def "FR23, M13: a child's stdin is touched only by the launch owner"() {
        expect: 'the scan reached every allowlisted file, each still touches a stdin, and no other file does'
        matching { touchesStdin(it) } == STDIN_TOUCHERS.keySet().sort()
    }

    // FR21, M13, D19: a launcher spelled outside the owner's helper is a second grouped form beside it.
    def "FR21, M13: the launcher prefix is spelled only by the launch owner's helper"() {
        expect: 'the scan reached the helper, it still spells the launcher, and no other file does'
        matching { spellsLauncher(it) } == [LAUNCHER]
    }

    // FR21, M13, D19 as amended 2026-10-17: the sweep's kill is the owner's second start, and its last.
    def "FR21, M13: the owner's file starts exactly the child and the group sweep's kill"() {
        given:
        String code = RepoSourceTree.code(RepoSourceTree.productionSources {
            it == OWNER
        }.find())

        expect: 'two no-arg starts, the kill spelled from the helper beside the second, and no other launch shape'
        (code =~ NO_ARG_START).count == OWNER_STARTS
        code.contains('new ProcessBuilder(launcher.kill()')
        DIRECT_STARTS.every { !(code =~ it) }
    }

    // FR21, D19 fourth amendment: a registry written elsewhere could hide a group from the shutdown
    //     step or forget one that still has members; a second caller of its sweep is a second step.
    def "FR21: the live group registry is written only by the owner and the sweep, and swept only by the shutdown step"() {
        given:
        String registry = RepoSourceTree.code(RepoSourceTree.productionSources {
            it == SUBPROCESS + 'LiveGroups.java'
        }.find())

        expect: 'the scan reached both writers, each still writes, and no other file does'
        matching { code ->
            code =~ REGISTRY_WRITE && code =~ REGISTRY_TYPE
        } == [OWNER, SWEEP]

        and: 'nothing outside the module can reach a writer, and the shutdown step alone sweeps'
        registry =~ /(?m)^final class LiveGroups\b/
        matching { code -> code =~ SWEEP_LIVE } == [SHUTDOWN_STEP]

        and: 'the detector: a write needs the registry named, and a comment is no write'
        [
            'LiveGroups g; g.register(pid);',
            'LiveGroups g; g . deregister (pid);'
        ].every {
            String c = RepoSourceTree.stripComments(it)
            c =~ REGISTRY_WRITE && c =~ REGISTRY_TYPE
        }
        [
            'registry.register(Type.class, supplier);',
            'LiveGroups g; // g.register(pid);'
        ].every {
            String c = RepoSourceTree.stripComments(it)
            !(c =~ REGISTRY_WRITE && c =~ REGISTRY_TYPE)
        }
    }

    def "the launcher detector: #shape"() {
        expect:
        spellsLauncher(RepoSourceTree.stripComments(source)) == detected

        where:
        shape | source || detected
        'the Linux helper' | 'List.of("setsid", exe);' || true
        'the perl call' | 'String s = "use POSIX (); POSIX::setsid(); exec";' || true
        'the macOS helper' | 'Path perl = Path.of("/usr/bin/perl");' || true
        'a commented helper' | '// prefixed with setsid' || false
        'a longer word' | 'boolean setsidAvailable = true;' || false
    }

    def "the stdin detector: #shape"() {
        expect:
        touchesStdin(RepoSourceTree.stripComments(source)) == detected

        where:
        shape | source || detected
        'the stream opened' | 'try (OutputStream os = process.getOutputStream()) {' || true
        'the stream spaced' | 'OutputStream os = process.getOutputStream ( );' || true
        'a file redirect' | 'builder.redirectInput(file.toFile());' || true
        'a redirect spaced' | 'builder . redirectInput (Redirect.PIPE);' || true
        'a redirect read' | 'Redirect r = builder.redirectInput();' || true
        'a stdout read' | 'InputStream in = process.getInputStream();' || false
        'an output redirect' | 'builder.redirectOutput(file);' || false
        'a commented write' | '// process.getOutputStream().write(bytes);' || false
    }

    // Over a clean tree the scan finds only the owners, so only seeded sources tell a working
    //     detector from one a new spelling walks past.
    def "the start detector: #shape"() {
        expect:
        starts(RepoSourceTree.stripComments(source)) == detected

        where:
        shape | source || detected
        'a builder started' | 'ProcessBuilder b = new ProcessBuilder(cmd); return b.start();' || true
        'a chained start' | 'Process p = new ProcessBuilder(cmd).start();' || true
        'a start beside the role' | 'ProcessLaunch l = b -> b.start();' || true
        'a method reference' | 'launch(ProcessBuilder::start);' || true
        'a pipeline' | 'ProcessBuilder.startPipeline(builders);' || true
        'the runtime' | 'Runtime.getRuntime().exec(cmd);' || true
        'a thread started' | 'Thread t = Thread.ofVirtual().unstarted(r); t.start();' || false
        'a launch through the role' | 'Process p = launch.start(new ProcessBuilder(cmd));' || false
        'a commented start' | 'ProcessBuilder b; // b.start();' || false
    }

    def "the implementation detector: #shape"() {
        expect:
        implementsLaunch(RepoSourceTree.stripComments(source)) == detected

        where:
        shape | source || detected
        'a class' | 'final class PlainLaunch implements ProcessLaunch {' || true
        'a second interface' | 'record X(int a) implements Closeable, ProcessLaunch {' || true
        'a sub-interface' | 'interface Wider extends ProcessLaunch {' || true
        'an anonymous class' | 'return new ProcessLaunch() {' || true
        'a field of the role' | 'private final ProcessLaunch launch;' || false
        'a javadoc mention' | '/** implements ProcessLaunch */ class Y {' || false
    }

    /** The relative paths of the production sources outside test-fixtures whose code matches. */
    private static List<String> matching(Closure<Boolean> detector) {
        def sources = RepoSourceTree.productionSources { String path ->
            !path.startsWith('test-fixtures/')
        }
        assert sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        sources.findAll {
            detector(RepoSourceTree.code(it))
        }.collect {
            RepoSourceTree.relative(it)
        }.sort()
    }

    private static boolean starts(String code) {
        DIRECT_STARTS.any {
            code =~ it
        } || (code =~ NO_ARG_START && code =~ LAUNCH_TYPES)
    }

    private static boolean spellsLauncher(String code) {
        LAUNCHER_SHAPES.any { code =~ it }
    }

    private static boolean touchesStdin(String code) {
        STDIN_SHAPES.any { code =~ it }
    }

    private static boolean implementsLaunch(String code) {
        IMPLEMENTATIONS.any { code =~ it }
    }
}
