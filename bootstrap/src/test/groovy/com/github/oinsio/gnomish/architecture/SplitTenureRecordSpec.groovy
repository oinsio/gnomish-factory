package com.github.oinsio.gnomish.architecture

import java.nio.file.Files
import java.nio.file.Path
import javax.tools.ToolProvider
import spock.lang.PendingFeature
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR4, FR5 of fix-claim-epoch-fence: the tenure record has one owner, and an assembly in which the
 * git writers stamp from one record while the tracker fills another is meant not to compile.
 *
 * <p>Probed with the real compiler over the production classpath: a single-book assembly — the
 * shape {@code ManualRunConfiguration} builds — must compile, which proves the probe itself works;
 * the split assembly, written the same way with a second book, must not. Today the writers take
 * their {@code ClaimEpochSource} as a constructor argument of their own, beside the book {@code
 * TaskGit} carries, so the split compiles and only the whole-tree scan of {@link
 * ClaimlessGitBoundarySpec} stands in the way (finding F74 of tighten-spec-contract).
 */
class SplitTenureRecordSpec extends Specification {

    @TempDir
    Path tempDir

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
'''

    /** An assembly whose writers stamp from {@code stamped} and whose tracker fills {@code filled}. */
    private static String assembly(String stamped, String filled) {
        IMPORTS + """
final class Assembly {
    static Tracker assemble(GitProcessRunner runner, GitInfrastructureRetry retry, InstantSource clock, Tracker adapter) {
        ClaimEpochBook one = new ClaimEpochBook();
        ClaimEpochBook two = new ClaimEpochBook();
        TaskGit git = new TaskGit(
                new GitTaskStore(runner, ${stamped}, retry, clock),
                new GitTaskBranches(runner, ${stamped}, retry),
                new GitTaskWorktrees(runner, ${stamped}),
                ${filled});
        return new EpochRecordingTracker(adapter, git.epochs());
    }
}
"""
    }

    private boolean compiles(String source) {
        Path dir = Files.createDirectories(tempDir.resolve(UUID.randomUUID().toString()))
        Path file = dir.resolve('Assembly.java')
        Files.writeString(file, source)
        def out = new ByteArrayOutputStream()
        int exit = ToolProvider.systemJavaCompiler.run(null, out, out,
                '-proc:none', '-d', dir.toString(), '-cp', System.getProperty('java.class.path'), file.toString())
        exit == 0
    }

    // The control: the probe compiles the production shape, so a refusal below is the split's own
    def "FR4: an assembly whose writers and tracker share one book compiles"() {
        expect:
        compiles(assembly('one', 'one'))
    }

    // FR4: the stamping half and the recording half must be one object, by the compiler's hand
    // Scenario: lifecycle/task-branch-contract / A split tenure record cannot be assembled
    @PendingFeature(reason = 'F74 (close-assembly-escape-hatches): the git writers take their ClaimEpochSource as an argument of their own, so a two-book assembly compiles; only ClaimlessGitBoundarySpec refuses it')
    def "FR4: an assembly whose writers stamp from one book while the tracker fills another does not compile"() {
        expect:
        !compiles(assembly('one', 'two'))
    }
}
