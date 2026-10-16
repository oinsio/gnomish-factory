package com.github.oinsio.gnomish.buildlogic

import java.nio.file.Path
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner

/**
 * A miniature repository for {@code SpecContractCheck}'s functional specs: a spec tree under
 * {@code openspec/specs}, change deltas under {@code openspec/changes}, a test tree under
 * {@code mod/src/test}, exemption files under {@code config/spec-contract-exemptions}, and a
 * build that registers the task with its default inputs and what a scenario configures, or
 * applies {@code spec-contract-conventions} as the root build does. The task type is put on the build script's classpath
 * straight from TestKit's metadata, so nothing is resolved and every run is {@code --offline}.
 *
 * <p>This file lives in a tree the scenario gate scans: a test-source fixture spells the marker
 * through {@link #MARKER}, never with the marker at the start of a source line.
 */
final class MiniSpecContractProject {

    static final String MARKER = '/' + '/ Scenario:'

    /** A valid spec of one requirement and one scenario, {@code demo / Works}. */
    static final String DEMO_SPEC = '''\
# demo

## Requirements

### Requirement: Demo behaves
The demo SHALL work.
**Implements**: FR1 of demo-change

#### Scenario: Works
- **WHEN** it runs
- **THEN** it works
'''

    /** A delta adding the scenario {@code demo / New thing happens}. */
    static final String ADDED_DELTA = '''\
## ADDED Requirements

### Requirement: New thing
The demo SHALL do a new thing.
**Implements**: FR1 of feat

#### Scenario: New thing happens
- **THEN** it happens
'''

    /** A MODIFIED section restating {@code Demo behaves} with a second scenario, {@code demo / Works twice}. */
    static final String MODIFIED_SECTION = '''
## MODIFIED Requirements

### Requirement: Demo behaves
The demo SHALL work, twice.
**Implements**: FR1 of demo-change; FR2 of feat

#### Scenario: Works
- **THEN** it works

#### Scenario: Works twice
- **THEN** it works again
'''

    /** A delta removing a requirement whose scenario is {@code demo / Old thing happens}. */
    static final String REMOVED_DELTA = '''\
## REMOVED Requirements

### Requirement: Old thing
**Reason**: retired.
**Migration**: none.

#### Scenario: Old thing happens
- **THEN** it happened
'''

    final Path dir

    MiniSpecContractProject(Path dir) {
        this.dir = dir
        write('settings.gradle', "rootProject.name = 'mini-spec-contract'\n")
        configure('')
    }

    /** The valid starting point: the demo spec, and a test linking its one scenario. */
    MiniSpecContractProject valid() {
        spec('demo', DEMO_SPEC)
        test('DemoSpec', "${MARKER} demo / Works")
    }

    /** Rewrites the build, {@code body} added inside the task's configuration block. */
    MiniSpecContractProject configure(String body) {
        buildFile("import com.github.oinsio.gnomish.buildlogic.SpecContractCheck\n\n"
                + "tasks.register('checkSpecContract', SpecContractCheck) {\n${body}\n}\n")
    }

    /** Rewrites the build to register the task as the root build does: the convention plugin. */
    MiniSpecContractProject conventions() {
        buildFile("apply plugin: 'spec-contract-conventions'\n")
    }

    private MiniSpecContractProject buildFile(String script) {
        String classpath = GradleRunnerSupport.pluginClasspath()
                .collect { "'" + GradleRunnerSupport.quotedPath(it) + "'" }.join(', ')
        write('build.gradle', "buildscript {\n    dependencies {\n        classpath files(${classpath})\n    }\n}\n\n${script}")
    }

    MiniSpecContractProject spec(String specId, String text) {
        write("openspec/specs/${specId}/spec.md", text)
    }

    /** Writes the exemption file {@code name} under {@code config/spec-contract-exemptions}, one line per entry. */
    MiniSpecContractProject exemptions(String name, String... lines) {
        write("config/spec-contract-exemptions/${name}", lines.join('\n') + '\n')
    }

    MiniSpecContractProject delta(String changePath, String specId, String text) {
        write("openspec/changes/${changePath}/specs/${specId}/spec.md", text)
    }

    /** A Groovy test source whose class body is {@code lines}, one per source line. */
    MiniSpecContractProject test(String name, String... lines) {
        write("mod/src/test/groovy/demo/${name}.groovy",
                "package demo\n\nclass ${name} {\n${lines.collect { '    ' + it }.join('\n')}\n    def feature() {}\n}\n")
    }

    BuildResult passes(String... arguments) {
        runner(arguments).build()
    }

    BuildResult fails(String... arguments) {
        runner(arguments).buildAndFail()
    }

    /**
     * Fails, and reports {@code finding} as rendered: verbatim in the report file, and in the
     * console output, where Gradle indents every line of the failure after the first.
     */
    boolean failsReporting(String finding) {
        String output = fails().output
        output.contains(finding.replace('\n', '\n  ')) && report().contains(finding)
    }

    String report() {
        dir.resolve('build/reports/spec-contract.txt').toFile().getText('UTF-8')
    }

    /**
     * No {@code --stacktrace}: what the operator sees is what the specs assert (UX1). No {@code
     * withPluginClasspath()} either: a task type TestKit injects is loaded by a class loader Gradle
     * cannot fingerprint, so the task would never be up to date; the build script's own classpath
     * carries it instead.
     */
    GradleRunner runner(String... arguments) {
        commandLine(['checkSpecContract', *arguments])
    }

    /** A runner given exactly {@code arguments} — task names included — as a launcher spells them. */
    GradleRunner commandLine(List<String> arguments) {
        GradleRunner.create()
                .withProjectDir(dir.toFile())
                .withArguments([*arguments, '--offline', '-g', GradleRunnerSupport.gradleUserHome()])
                .forwardOutput()
    }

    private MiniSpecContractProject write(String path, String content) {
        GradleRunnerSupport.writeFile(dir, path, content)
        this
    }
}
