package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import spock.lang.Specification

/**
 * FR16, M1, M2 and design D1, D2, D5, D14 of supervise-daemon-loops-and-embed-dashboard: a
 * long-lived daemon thread has one owner, {@code SupervisedLoop}, and so do its restart policy and
 * its log roll-up period (single-owner rows 1–3). FR9, FR14 and D10: the dashboard page has one
 * assembly, {@code DashboardWatch}, owning its output name and the board composition it renders.
 * FR10, NFR-S1 and D11 (single-owner row 4): the read-only tracker resolution stays out of
 * {@code serve}, reached only by the standalone {@code dashboard} and {@code board} commands.
 *
 * <p>The defect this gate exists for had a green build: every daemon loop started its own virtual
 * thread, ran its own {@code while} and caught its own failures, so one of them could die silently
 * while every unit spec of every loop stayed green. A new loop written the old way is outside every
 * allowlist below by default. The scope is {@code application/src/main}, the layer holding every
 * daemon loop; subprocess and stream pumps in other modules are finite threads. The {@link
 * ClaimlessGitBoundarySpec} shape: paths, not patterns, comments stripped, and every allowlisted
 * file asserted reached so a moved or stale exemption fails instead of widening the gate.
 */
class DaemonLoopOwnerBoundarySpec extends Specification {

    private static final String APP = 'application/src/main/java/com/github/oinsio/gnomish/'

    /** Thread creation: the shape a daemon loop of its own starts with. */
    private static final List<String> THREAD_CREATION = [
        'Thread.ofVirtual(',
        'Thread.ofPlatform(',
        'new Thread('
    ]

    /** The files allowed to create a thread, each with the reason it may (row 1). */
    private static final Map<String, String> THREAD_OWNERS = [
        (APP + 'app/daemon/SupervisedLoop.java'): 'the owner: the daemon thread, its guard, stop and restart (D1-D5)',
        (APP + 'app/lease/HeldClaims.java'): 'the heartbeat worker, not resurrected by D3 of add-claim-heartbeat',
        (APP + 'app/serve/FeedCycle.java'): 'one finite thread per claimed task',
        (APP + 'app/TakeBatch.java'): 'a finite batch of take runs',
        (APP + 'app/ServeShutdownWiring.java'): 'the feed thread owns the daemon lifetime; JVM shutdown hooks',
    ]

    /** A scheduler: the silent-death shape D1 rejects, since a throwing task cancels its own schedule. */
    private static final List<String> SCHEDULERS = [
        'Executors.newScheduled',
        'Executors.newSingleThreadScheduled',
        'ScheduledExecutorService',
        'new Timer(',
    ]

    /** Row 2 (D5): the restart backoff is built only by the policy and by the probe schedule. */
    private static final Set<String> BACKOFF_OWNERS = [
        APP + 'app/daemon/RestartPolicy.java',
        APP + 'app/serve/RemoteOutageProbeSchedule.java',
    ] as Set

    /**
     * Row 3 (D2): the loop files and the heartbeat timing never build a suppressor of their own —
     * the loop builds it from its clock. The dashboard loop is {@code app/DashboardWatch.java}
     * (task 8.1).
     */
    private static final List<String> NO_OWN_SUPPRESSOR = [
        APP + 'app/lease/StandingReaper.java',
        APP + 'app/serve/WorktreeJanitor.java',
        APP + 'app/serve/SandboxLifecycleTick.java',
        APP + 'serveobservability/writer/SnapshotWriter.java',
        APP + 'app/DashboardWatch.java',
        APP + 'app/lease/BeatTiming.java',
    ]

    /** Split at the parenthesis: the test-time gate reads any {@code .system(} in a test source as real time. */
    private static final List<String> OWN_SUPPRESSOR = [
        'RepeatSuppressor.system' + '(',
        'new RepeatSuppressor('
    ]

    /** Row 3 (D2): the catalog default is read only where the period is derived (bare, so a static import is caught too). */
    private static final String ROLL_UP_DEFAULT = 'DEFAULT_ROLL_UP_INTERVAL'
    private static final Set<String> ROLL_UP_OWNERS = [
        APP + 'app/daemon/RollUpPeriod.java'
    ] as Set

    /** D10: the page's file name is spelled only by the dashboard assembly. */
    private static final String PAGE_NAME = '"dashboard.html"'
    private static final Set<String> PAGE_NAME_OWNERS = [
        APP + 'app/DashboardWatch.java'
    ] as Set

    /**
     * D10: the board is composed only by the dashboard assembly and by the standalone {@code board}
     * command. The declaration ({@code static BoardModel compose(}) carries no qualifier, so it is
     * not a match; a method reference is a call site too.
     */
    private static final List<String> BOARD_COMPOSE = [
        'BoardComposition.compose(',
        'BoardComposition::compose'
    ]
    private static final Set<String> BOARD_COMPOSE_OWNERS = [
        APP + 'app/DashboardWatch.java',
        APP + 'app/BoardCommand.java'
    ] as Set

    /** A static import would let a bare {@code compose(} call evade the qualified marker above. */
    private static final String BOARD_STATIC_IMPORT = 'import static com.github.oinsio.gnomish.board.BoardComposition.'

    /**
     * Row 4 (D11): the read-only tracker resolution, which reads a {@code SecretsProvider} from a
     * directory, is declared by the tracker wiring and called only by the two standalone read-only
     * commands; inside {@code serve} the board reader comes from {@code BoundTracker}. The bare name
     * matches the declaration, a qualified call and a method reference alike.
     */
    private static final List<String> READ_ONLY_RESOLUTION = [
        'resolveReadOnly(',
        '::resolveReadOnly'
    ]
    private static final Set<String> READ_ONLY_RESOLUTION_OWNERS = [
        APP + 'app/TrackerWiring.java',
        APP + 'app/DashboardCommand.java',
        APP + 'app/BoardCommand.java'
    ] as Set

    // FR16, M1, D1: a thread started outside the owner is a daemon loop nobody supervises.
    def "FR16, M1: only the supervised loop and the four finite-thread owners create a thread"() {
        expect: 'the scan reached every allowlisted file, each still creates a thread, and no other file does'
        spelling(THREAD_CREATION) == THREAD_OWNERS.keySet()
    }

    // FR16, D1: a scheduled task that throws is cancelled without a trace — the silent death.
    def "FR16, D1: no scheduler spells #token in application"() {
        expect: 'the scan reached the tree'
        unreached(THREAD_OWNERS.keySet()).isEmpty()

        and: 'no file spells it'
        spelling([token]).isEmpty()

        where:
        token << SCHEDULERS
    }

    // FR16, M2, D5: a second construction site of the backoff is a second restart policy.
    def "FR16, M2, D5: the restart backoff is built only by its two owners"() {
        expect:
        spelling(['new RestartBackoff(']) == BACKOFF_OWNERS
    }

    // D2: a loop holding its own suppressor rolls up on a period nobody derived from its interval.
    def "D2: no loop file and no heartbeat timing builds a suppressor of its own"() {
        expect: 'the scan reached every listed file'
        unreached(NO_OWN_SUPPRESSOR).isEmpty()

        and: 'none of them builds one'
        (spelling(OWN_SUPPRESSOR) intersect NO_OWN_SUPPRESSOR).isEmpty()
    }

    // D2: the roll-up period has one producer; a second reader of the default is a second period.
    def "D2: the roll-up default is read only by the roll-up period"() {
        expect:
        spelling([ROLL_UP_DEFAULT]) == ROLL_UP_OWNERS
    }

    // FR9, FR14, D10: a second spelling of the page name is a second output path for one page.
    def "FR9, FR14, D10: the dashboard page name is spelled only by the dashboard assembly"() {
        expect: 'the scan reached the owner, it still spells the name, and no other file does'
        spelling([PAGE_NAME]) == PAGE_NAME_OWNERS
    }

    // FR9, FR14, D10: a third composition site is a second dashboard renderer beside the assembly.
    def "FR9, FR14, D10: the board is composed only by the dashboard assembly and the board command"() {
        expect: 'the scan reached both owners, each still composes, and no other file does'
        spelling(BOARD_COMPOSE) == BOARD_COMPOSE_OWNERS

        and: 'no file reaches the composition through a static import the qualified marker cannot see'
        spelling([BOARD_STATIC_IMPORT]).isEmpty()
    }

    // FR10, NFR-S1, D11: a serve-side call re-resolves the tracker from a directory beside the bound one.
    def "FR10, NFR-S1, D11: the read-only tracker resolution is reached only by the two standalone commands"() {
        expect: 'the scan reached the declaration and both callers, each still spells it, and no other file does'
        spelling(READ_ONLY_RESOLUTION) == READ_ONLY_RESOLUTION_OWNERS
    }

    private static List<File> applicationSources() {
        RepoSourceTree.productionSources { String path ->
            path.startsWith('application/src/main/')
        }
    }

    /** The listed paths the scan did not reach: moved, renamed or deleted files. */
    private static Set<String> unreached(Collection<String> paths) {
        paths.toSet() - applicationSources().collect {
            RepoSourceTree.relative(it)
        }.toSet()
    }

    /** The application files spelling any of the tokens in code, outside comments. */
    private static Set<String> spelling(List<String> tokens) {
        applicationSources().findAll { file ->
            def code = RepoSourceTree.code(file)
            tokens.any { code.contains(it) }
        }
        .collect { RepoSourceTree.relative(it) }
        .toSet()
    }
}
