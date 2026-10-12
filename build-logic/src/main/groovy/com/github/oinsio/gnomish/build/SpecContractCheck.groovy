package com.github.oinsio.gnomish.build

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option

/**
 * The one checker of the project's own spec-contract rules (design D5 of tighten-spec-contract):
 * the traceability line and the retired comment form ({@link TraceabilityLineRule}, FR1), the
 * notes ({@link SpecNotesRule}, FR2) and the scenario-to-test link ({@link ScenarioLinkRule},
 * FR5). Root {@code check}, {@code scripts/check-spec-contract.sh} and the fix-artifacts and
 * archive stages all run this task; none of them holds a rule of its own.
 *
 * <p><b>Inputs.</b> The main spec tree ({@code openspec/specs}, or the directory {@link
 * #getSpecsDir} names, so a previewed tree can stand in for it); the unarchived deltas under
 * {@code openspec/changes} when {@link #getChanges} is set — judged by FR1 and FR2 only, never
 * linked (proposal NG6), and {@code archive/} never read; the test trees ({@code *}{@code
 * /src/test}, {@code *}{@code /*}{@code /src/test}, {@code build-checks/*}{@code /src/test},
 * {@code build-logic/src/functionalTest}); the exemption files under {@link #getExemptionsDir}.
 * {@link #getSpec} narrows the run to some spec ids; {@link #getChange} narrows the deltas to one
 * change's, so a stage working on that change is not judged by another change's defect.
 *
 * <p><b>Files only</b> (design D6, NFR-R1, NFR-S1): it reads text and matches fixed patterns; no
 * process, no git, no OpenSpec, no character counting. Text it quotes is data, verbatim.
 *
 * <p>Implements FR1, FR2, FR5, FR6, NFR-R1, NFR-O1, NFR-S1 and UX1 of tighten-spec-contract.
 */
@CacheableTask
abstract class SpecContractCheck extends DefaultTask {

    /** Where the exemption files live, relative to the root project. */
    static final String EXEMPTIONS_DIR = 'config/spec-contract-exemptions'

    SpecContractCheck() {
        File root = project.projectDir
        baseDir.convention(project.layout.projectDirectory)
        specsDir.convention(project.layout.projectDirectory.dir('openspec/specs'))
        changesDir.convention(project.layout.projectDirectory.dir('openspec/changes'))
        changes.convention(false)
        change.convention('')
        spec.convention('')
        File exemptions = new File(project.rootDir, EXEMPTIONS_DIR)
        exemptionsDir.convention(project.layout.dir(project.provider { exemptions.isDirectory() ? exemptions : null }))
        testSources.from(project.fileTree(root) {
            include '*/src/test/**', '*/*/src/test/**', 'build-checks/*/src/test/**', 'build-logic/src/functionalTest/**'
        })
        report.convention(project.layout.buildDirectory.file('reports/spec-contract.txt'))
    }

    @Inject
    abstract ObjectFactory getObjects()

    /** The main spec tree: every {@code spec.md} under it is a main spec, its spec id its directory. */
    @InputDirectory
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getSpecsDir()

    /** Where the unarchived changes live; read only when {@link #getChanges} is set. */
    @Internal
    abstract DirectoryProperty getChangesDir()

    /** Whether the unarchived deltas are judged too (FR1, FR2 only). */
    @Input
    @Option(option = 'changes', description = 'Also judge the deltas of unarchived changes (traceability line, notes).')
    abstract Property<Boolean> getChanges()

    /**
     * The one unarchived change whose deltas are judged; blank judges every change's. Naming one
     * implies {@link #getChanges}: the main specs are judged as without it.
     */
    @Input
    @Option(option = 'change', description = 'Judge the deltas of this unarchived change only (implies --changes).')
    abstract Property<String> getChange()

    /** Comma-separated spec ids to judge; blank judges every spec. */
    @Input
    @Option(option = 'spec', description = 'Comma-separated spec ids to judge (default: all).')
    abstract Property<String> getSpec()

    /**
     * The exemption files, one per capability ({@link ExemptionFile}); absent when the directory
     * does not exist, which means no exemptions.
     */
    @InputDirectory
    @Optional
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getExemptionsDir()

    /** Test sources whose {@code // Scenario:} markers link scenarios. */
    @InputFiles
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getTestSources()

    /** Findings are reported relative to this directory. */
    @Internal
    abstract DirectoryProperty getBaseDir()

    /** Written on every run; the gate's output, so a second run with unchanged inputs is up to date. */
    @OutputFile
    abstract RegularFileProperty getReport()

    /**
     * The deltas of unarchived changes, when {@link #getChanges} is set, or of the one change
     * {@link #getChange} names; never {@code archive/}.
     */
    @InputFiles
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    FileCollection getDeltaSpecs() {
        String named = change.get()
        if (!changes.get() && named.isEmpty()) {
            return objects.fileCollection()
        }
        objects.fileTree().from(changesDir).matching {
            include "${named.isEmpty() ? '*' : named}/specs/**/spec.md"
            exclude 'archive/**'
        }
    }

    @TaskAction
    void check() {
        File base = baseDir.get().asFile
        SpecContractCheck.requireChange(changesDir.get().asFile, change.get())
        Set<String> scope = spec.get().split(',')*.trim().findAll { it } as Set<String>
        Closure<Boolean> inScope = { String specId -> scope.isEmpty() || specId in scope }
        File specsRoot = specsDir.get().asFile
        List<SpecFile> mains = SpecFile.readAll(base, objects.fileTree().from(specsRoot).files) { specsRoot }
        File changesRoot = changesDir.get().asFile
        List<SpecFile> deltas = SpecFile.readAll(base, deltaSpecs.files) { File file -> SpecContractCheck.deltaRoot(changesRoot, file) }
        List<SpecContractFinding> findings = (mains + deltas).findAll { inScope(it.specId) }.collectMany {
            TraceabilityLineRule.judge(it) + SpecNotesRule.judge(it)
        }
        Map<String, ScenarioMarkerReader.Markers> markers = testSources.files.findAll { it.isFile() }
                .collectEntries { [(SpecFile.relative(base, it)): ScenarioMarkerReader.read(it)] }
        File exemptionsRoot = exemptionsDir.getOrNull()?.asFile
        List<File> exemptionFiles = (exemptionsRoot?.listFiles() ?: new File[0]).findAll { it.isFile() && !it.name.startsWith('.') }
        ScenarioExemptions exemptionList = ScenarioExemptions.read(base, exemptionFiles, inScope)
        ScenarioLinkRule.Verdict links = ScenarioLinkRule.judge(mains, markers, exemptionList, scope)
        findings.addAll(links.findings)
        SpecContractReport.write(report.get().asFile, findings.sort(), links)
        if (!findings.isEmpty()) {
            throw new GradleException(SpecContractReport.failure(findings, links))
        }
        logger.lifecycle(SpecContractReport.summary(links))
    }

    /**
     * A named change must be one unarchived change directory: a name that is a path, a pattern,
     * {@code archive} or a typo would otherwise judge no delta and pass. Called through the class
     * name, as {@link #deltaRoot} is.
     */
    private static void requireChange(File changesRoot, String named) {
        if (named.isEmpty()) {
            return
        }
        if (!(named ==~ /[a-z0-9][a-z0-9-]*/) || named == 'archive' || !new File(changesRoot, named).isDirectory()) {
            throw new GradleException("no unarchived change named '${named}' under ${changesRoot}")
        }
    }

    /**
     * The {@code specs/} directory of the change a delta belongs to: its spec id is relative to it.
     * Called through the class name: a closure's unqualified call dispatches on the decorated task
     * subclass, which cannot see a private method once a plugin registers the task.
     */
    private static File deltaRoot(File changesRoot, File file) {
        String change = SpecFile.relative(changesRoot, file).split('/')[0]
        new File(new File(changesRoot, change), 'specs')
    }
}
