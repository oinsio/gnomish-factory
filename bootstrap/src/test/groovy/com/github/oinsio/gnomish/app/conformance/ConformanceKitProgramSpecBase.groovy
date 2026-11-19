package com.github.oinsio.gnomish.app.conformance

import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.RepositoryInit
import com.github.oinsio.gnomish.app.describe.ProgramChannel
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The world of the kit's specs against real candidate processes (FR12, FR24 of
 * define-executor-contract): each candidate run wrapped in the factory's own {@code ProgramExecutor}
 * through {@link ProgramCandidate}, each case in a git repository of its own, every launch recorded
 * with the scratch area its result file was named in.
 */
abstract class ConformanceKitProgramSpecBase extends Specification {

    protected static final Map<String, Object> DEFER = [defer: true]

    @TempDir
    Path tempDir

    protected final List<List<String>> launched = []
    protected final Set<Path> scratchAreas = [] as Set
    /** Each start and poll launched: its operation, the working copy it ran in, the pending key it carried. */
    protected final List<Map<String, Object>> operations = []
    /** The scratch root of the last candidate verified: the cases' working copies and their poll copies. */
    protected Path kitScratch

    protected KitReport verify(Path program, Map<String, Object> pendingSettings = [:]) {
        def counted = { ProcessBuilder builder, StdinSource stdin ->
            launched << List.copyOf(builder.command())
            String written = builder.environment()[ProgramChannel.RESULT_FILE_VARIABLE]
            if (written != null) {
                scratchAreas << Path.of(written).parent
            }
            if (builder.command().last() in ['start', 'poll']) {
                operations << [op: builder.command().last(), dir: builder.directory().toPath(),
                    key: builder.environment()[ProgramChannel.PENDING_KEY_VARIABLE]]
            }
            ProcessLaunch.grouped().start(builder, stdin)
        } as ProcessLaunch
        def equipment = new KitEquipment(VirtualTimeEquipment.create(), TestChildEnvironment.hostAllowlist(), counted,
                new RepositoryInit(new GitProcessRunner()), Files.createTempDirectory(tempDir, 'stderr-'))
        kitScratch = Files.createTempDirectory(tempDir, 'kit-')
        def candidate = new ProgramCandidate([program.toString()], pendingSettings, kitScratch, equipment)
        new ConformanceKit(pendingSettings, equipment.time().sleeper()).run(candidate)
    }

    protected static List<KitCase> failed(KitReport report) {
        report.verdicts().findAll {
            it.value instanceof CaseVerdict.Failed
        }.keySet().toList()
    }
}
