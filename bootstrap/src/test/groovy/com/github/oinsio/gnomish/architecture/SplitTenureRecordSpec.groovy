package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR4, FR5 of fix-claim-epoch-fence: the tenure record has one owner, and an assembly in which the
 * git writers stamp from one record while the tracker fills another does not compile.
 *
 * <p>Probed with the real compiler over the production classpath. The control writes the shape
 * {@code ManualRunConfiguration} builds — the public assembly {@code GitTaskBackend.taskGit}, which
 * builds the three writers over the one book it puts into the {@code TaskGit} it returns — and must
 * compile, which proves the probe itself works. The split shape builds the writers by hand over a
 * second book beside the bundle's; since F74 of tighten-spec-contract (design D12) the writers'
 * constructors are package-private to {@code :adapters:git}, so the split no longer type-checks
 * anywhere outside that package.
 *
 * <p>The compiler holds that line for Java only: Groovy does not enforce package-private access,
 * so a spec could still build the writers by hand. The third feature closes that half with a
 * whole-tree scan of every source outside {@code :adapters:git}, whose own specs are the one place
 * the writers are built directly (each a single component over its own source, never a bundle).
 */
class SplitTenureRecordSpec extends Specification {

    @TempDir
    Path tempDir

    /** The hand-built writer constructions only {@code :adapters:git} may spell. */
    private static final List<String> WRITER_CONSTRUCTIONS = [
        'new GitTaskStore(',
        'new GitTaskBranches(',
        'new GitTaskWorktrees(',
    ]

    /** The writers whose constructors only {@code :adapters:git} may call. */
    private static final List<String> WRITERS = [
        'GitTaskStore',
        'GitTaskBranches',
        'GitTaskWorktrees'
    ]

    /** The one module whose sources build the writers directly: the package that declares them. */
    private static final String WRITER_MODULE = 'adapters/git/'

    /** This file: its probe sources spell every writer construction, as the split shape. */
    private static final String THIS_SPEC =
    'bootstrap/src/test/groovy/com/github/oinsio/gnomish/architecture/SplitTenureRecordSpec.groovy'

    private static final String IMPORTS = '''\
import com.github.oinsio.gnomish.adapter.git.GitInfrastructureRetry;
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.GitTaskBranches;
import com.github.oinsio.gnomish.adapter.git.GitTaskStore;
import com.github.oinsio.gnomish.adapter.git.GitTaskWorktrees;
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook;
import com.github.oinsio.gnomish.app.lease.EpochRecordingTracker;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import java.time.InstantSource;
import java.util.function.UnaryOperator;
'''

    /** The production shape: one book, handed to the assembly, travels in the bundle the tracker is wrapped with. */
    private static String assembly() {
        IMPORTS + '''import com.github.oinsio.gnomish.adapter.git.GitTaskBackend;

final class Assembly {
    static Tracker assemble(GitProcessRunner runner, GitInfrastructureRetry retry, InstantSource clock, Tracker adapter) {
        ClaimEpochBook one = new ClaimEpochBook();
        TaskGit git = GitTaskBackend.taskGit(runner, retry, clock, UnaryOperator.identity(), one);
        return new EpochRecordingTracker(adapter, git.epochs());
    }
}
'''
    }

    /** The split shape: writers stamping from {@code two} while the bundle — and so the tracker — carries {@code one}. */
    private static String split() {
        IMPORTS + '''
final class Assembly {
    static Tracker assemble(GitProcessRunner runner, GitInfrastructureRetry retry, InstantSource clock, Tracker adapter) {
        ClaimEpochBook one = new ClaimEpochBook();
        ClaimEpochBook two = new ClaimEpochBook();
        TaskGit git = new TaskGit(
                new GitTaskStore(runner, two, retry, clock),
                new GitTaskBranches(runner, two, retry),
                new GitTaskWorktrees(runner, two),
                one);
        return new EpochRecordingTracker(adapter, git.epochs());
    }
}
'''
    }

    /** The compiler's diagnostics for {@code source}, or an empty string when it compiles. */
    private String compile(String source) {
        Path dir = Files.createDirectories(tempDir.resolve(UUID.randomUUID().toString()))
        Path file = dir.resolve('Assembly.java')
        Files.writeString(file, source)
        def out = new ByteArrayOutputStream()
        int exit = ToolProvider.systemJavaCompiler.run(null, out, out,
                '-proc:none', '-d', dir.toString(), '-cp', System.getProperty('java.class.path'), file.toString())
        exit == 0 ? '' : out.toString()
    }

    // The control: the probe compiles the production shape, so a refusal below is the split's own
    def "FR4: an assembly whose writers and tracker share one book compiles"() {
        expect:
        compile(assembly()).isEmpty()
    }

    // FR4: the stamping half and the recording half must be one object, by the compiler's hand —
    //     and for that reason alone: each writer's constructor is refused as package-private, and
    //     nothing else in the split shape is an error
    // Scenario: lifecycle/task-branch-contract / A split tenure record cannot be assembled
    def "FR4: an assembly whose writers stamp from one book while the tracker fills another does not compile"() {
        when:
        def errors = compile(split()).readLines().findAll {
            it.contains('error:')
        }

        then:
        errors.size() == WRITERS.size()
        WRITERS.every { writer ->
            errors.any {
                it.contains("${writer}(") && it.contains("is not public in ${writer};")
            }
        }
    }

    // FR4: Groovy compiles a package-private constructor call from any package, so the compiler's
    //     refusal above does not reach a spec; the scan does, over production and test sources alike
    def "FR4: no source outside the git adapter builds a git writer by hand"() {
        given: 'every production and test source outside the module that declares the writers'
        def outside = { String path -> !path.startsWith(WRITER_MODULE) }
        def sources = RepoSourceTree.productionSources(outside) + RepoSourceTree.testSources(outside)

        when: 'the files spelling a hand-built writer'
        def offenders = sources.findAll { file ->
            def code = RepoSourceTree.code(file)
            WRITER_CONSTRUCTIONS.any { code.contains(it) }
        }.collect { RepoSourceTree.relative(it) }.sort()

        then: 'only this spec, whose split probe spells them on purpose — which also proves the scan reached it'
        offenders == [THIS_SPEC]
    }
}
