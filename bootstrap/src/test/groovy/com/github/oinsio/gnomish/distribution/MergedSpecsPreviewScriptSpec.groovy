package com.github.oinsio.gnomish.distribution

import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The merged-spec preview, {@code scripts/check-merged-specs.sh} (FR4, NFR-R1, NFR-R2, UX1 of
 * split-long-spec-requirements; design D4, D5), driven over the committed OpenSpec presets of the
 * stand-in library ({@code tables/openspec.params}). CI has no OpenSpec CLI and none is pinned, so
 * what is pinned here is the script's own logic: the copy, the arguments, the exit codes, the
 * output and the cleanup; the real CLI's answers are the presets' data.
 *
 * <p>Each feature runs a copy of the script from a small repository in a temporary directory, so
 * the script's "run from the repository root" lands on a tree the spec owns; {@code OPENSPEC}
 * names a per-run link that records each invocation with its working directory, and
 * {@code TMPDIR} a directory the spec checks is empty afterwards.
 */
class MergedSpecsPreviewScriptSpec extends Specification {

    private static final Path SCRIPT = RepoSourceTree.repoRoot().resolve('scripts/check-merged-specs.sh')

    @TempDir
    Path tmp

    Path repo

    Path scratch

    Path openspec

    def setup() {
        repo = Files.createDirectories(tmp.resolve('repo'))
        Files.createDirectories(repo.resolve('scripts'))
        Files.copy(SCRIPT, repo.resolve('scripts/check-merged-specs.sh'))
        write('openspec/specs/tracker/github-tracker/spec.md', '# github-tracker\n\n## Requirements\n')
        write('openspec/changes/demo-change/proposal.md', '# Proposal: demo-change\n')
        write('openspec/changes/demo-change/specs/tracker/github-tracker/spec.md', '## ADDED Requirements\n')
        scratch = Files.createDirectories(tmp.resolve('scratch'))
        Files.createDirectories(tmp.resolve('bin'))
    }

    // Scenario: build-system/quality-gates / A MODIFIED requirement grown past the limit fails the preview
    def "FR4, UX1: a merged requirement over the limit exits 1 with the failing spec, OpenSpec's warning and what to do"() {
        given:
        def before = tree()

        when:
        def run = preview('openspec-preview-long', 'demo-change')

        then:
        run.exit == 1
        run.output.contains(StandIn.data('stderr-openspec#long-requirement'))
        run.output.contains('Totals: 2 passed, 1 failed (3 items)')
        !run.output.contains('✓ spec/')
        run.output.endsWith("check-merged-specs: the main specs merged with 'demo-change' do not validate strictly\n" +
                'check-merged-specs: split the requirement in the delta — one behavior per requirement — or, for a ' +
                'failure the change does not touch, escalate (.claude/rules/delta-specs.md)\n')
        asked() == [
            'archive demo-change --yes',
            'validate --specs --strict --no-interactive'
        ]
        tree() == before
        scratchLeft() == []
    }

    // Scenario: build-system/quality-gates / A passing preview leaves no trace
    def "FR4, NFR-R1: a passing preview exits 0, runs both commands in a copy outside the repository, and leaves no copy"() {
        given:
        def before = tree()

        when:
        def run = preview('openspec-preview-pass', 'demo-change')

        then:
        run.exit == 0
        run.output == "check-merged-specs: the main specs merged with 'demo-change' validate strictly\n"
        asked() == [
            'archive demo-change --yes',
            'validate --specs --strict --no-interactive'
        ]
        workingDirectories().every {
            it.startsWith(scratch.toString()) && !it.startsWith(repo.toString())
        }
        workingDirectories().size() == 2
        tree() == before
        scratchLeft() == []
    }

    // Scenario: build-system/quality-gates / A delta that no longer applies fails the preview
    def "FR4, NFR-R2: an archive that refuses exits 1 with the archive's own words and validates nothing"() {
        given:
        def before = tree()

        when:
        def run = preview('openspec-preview-archive-refused', 'demo-change')

        then:
        run.exit == 1
        run.output.startsWith(StandIn.data('stdout-openspec#modified-not-found'))
        run.output.contains("check-merged-specs: the archive of 'demo-change' refused (its words above): the delta no " +
                'longer applies to the main specs\n')
        asked() == ['archive demo-change --yes']
        tree() == before
        scratchLeft() == []
    }

    // Scenario: build-system/quality-gates / A preview without the CLI fails closed
    def "NFR-R2: #cause exits 1 naming the cause and runs no command"() {
        given:
        openspec = StandIn.link(tmp.resolve('bin/openspec'), 'openspec-preview-pass')

        when:
        def run = script(cli ?: openspec.toString(), args)

        then:
        run.exit == 1
        run.output.contains(message)
        !Files.exists(StandIn.log(openspec))
        scratchLeft() == []

        where:
        cause | cli | args || message
        'a CLI that cannot be run' | '/nonexistent' | ['demo-change'] || "the OpenSpec CLI '/nonexistent' cannot be run; put openspec on the PATH"
        'a name with no change' | null | ['other-change'] || "'other-change' is not an active change under openspec/changes/"
        'the archive directory' | null | ['archive'] || "'archive' is not a change name"
        'a name holding a separator' | null | ['demo;change'] || "'demo;change' is not a change name"
        'no name at all' | null | [] || 'usage: '
    }

    private Map preview(String preset, String change) {
        openspec = StandIn.link(tmp.resolve('bin/openspec'), preset)
        script(openspec.toString(), [change])
    }

    /** Each recorded invocation's arguments, in order. */
    private List<String> asked() {
        StandIn.log(openspec).toFile().readLines().findAll {
            it.startsWith('argv=')
        }.collect {
            it - 'argv='
        }
    }

    /** The working directory of each recorded invocation, in order. */
    private List<String> workingDirectories() {
        StandIn.log(openspec).toFile().readLines().findAll {
            it.startsWith('PWD=[')
        }.collect {
            it[5..-2]
        }
    }

    /** Every file under the repository's {@code openspec/}, by relative path, with its bytes. */
    private Map<String, List<Byte>> tree() {
        Files.walk(repo.resolve('openspec')).withCloseable { walk ->
            walk.filter { Files.isRegularFile(it) }.toList()
        }.collectEntries { Path file ->
            [(repo.relativize(file).toString()): Files.readAllBytes(file).toList()]
        }
    }

    private List<Path> scratchLeft() {
        Files.list(scratch).withCloseable { it.toList() }
    }

    private void write(String path, String text) {
        Path file = repo.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private Map script(String cli, List<String> args) {
        def builder = new ProcessBuilder([
            'bash',
            repo.resolve('scripts/check-merged-specs.sh').toString()
        ] + args)
        .directory(tmp.toFile())
        .redirectErrorStream(true)
        .redirectInput(ProcessBuilder.Redirect.from(new File('/dev/null')))
        def env = TestChildEnvironment.cleared(builder)
        env.OPENSPEC = cli
        env.TMPDIR = scratch.toString()
        def process = builder.start()
        def output = process.inputStream.text
        [exit: process.waitFor(), output: output]
    }
}
