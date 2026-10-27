package com.github.oinsio.gnomish.buildlogic

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import org.yaml.snakeyaml.Yaml
import spock.lang.Specification

/**
 * The fix-artifacts stage runs the merged-spec preview through one check of one shape (design D6
 * of split-long-spec-requirements), read from the REAL manifest with a YAML parser: the preamble
 * that names the task's change, exactly as the stage's other checks spell it, then the script, and
 * on failure the tail of its output, one {@code echo} of literal text and {@code exit 1}. The check
 * can therefore hold no rule of its own — the script owns the preview — and it never spells the
 * spec-contract task, so {@link StageSpecContractCommand#gradleArguments} still finds one launcher.
 * The single owner is kept so by a scan: no other stage or command file runs {@code openspec
 * archive} besides the ones whose subject is the archive itself.
 */
class StageMergedSpecsPreviewCommandSpec extends Specification {

    static final String SCRIPT = 'scripts/check-merged-specs.sh'

    /** The lines naming the task's change, exactly as the `openspec validate` check names it. */
    static final String CHANGE_PREAMBLE = '''\
# Reports live under temporary-docs/gnomish/<task>/: the task branch's name without its
# gnomish/ prefix (the factory's own sanitized taskId), 'local' off a task branch.
branch=$(git branch --show-current)
case "$branch" in gnomish/*) task=${branch#gnomish/} ;; *) task=local ;; esac
dir=temporary-docs/gnomish/$task
change=$(sed -n 's/^Change:[[:space:]]*//p' $dir/change.md | head -1)
'''

    private static final Pattern SHAPE = Pattern.compile(
            '^' + Pattern.quote(CHANGE_PREAMBLE)
                    + Pattern.quote('if ! out=$(' + SCRIPT + ' "$change" 2>&1); then\n')
                    + Pattern.quote('  printf \'%s\\n\' "$out" | tail -40\n')
                    + '  echo "(?:[^"$`\\\\\\n]|\\\\\\$)*' + Pattern.quote('.claude/rules/delta-specs.md') + '(?:[^"$`\\\\\\n]|\\\\\\$)*"\\n'
                    + '  exit 1\\n'
                    + 'fi\\n?$')

    /** The files whose subject is the archive itself, each with its reason. */
    private static final Map<String, String> ARCHIVE_OWNERS = [
        'commands/opsx/archive.md'              : 'the archive command runs the archive',
        'commands/opsx/sync.md'                 : 'the sync command describes what the archive does to a main spec',
        'gnomish/stages/archive/instructions.md': 'the archive stage runs the archive',
    ]

    // Scenario: build-system/quality-gates / The fix-artifacts round runs the preview after the change's own validation
    def "FR5: the fix-artifacts manifest runs the preview in one check of the pinned shape, after the change's own validation"() {
        given:
        List<String> commands = verifyCommands()
        List<Integer> previews = commands.findIndexValues { it.contains(SCRIPT) }*.intValue()
        int validation = commands.findIndexOf { it.contains('openspec validate "$change" --strict') }

        expect: 'exactly one check runs the script, right after the validation of the change'
        previews.size() == 1
        validation >= 0
        previews[0] == validation + 1

        and: 'its text is the pinned shape and never the spec-contract launcher'
        SHAPE.matcher(commands[previews[0]]).matches()
        !commands[previews[0]].contains(StageSpecContractCommand.TASK)
    }

    def "FR5: a preview check that #divergence is refused by the pinned shape"() {
        given:
        String pinned = verifyCommands().find { it.contains(SCRIPT) }

        expect:
        !SHAPE.matcher(change(pinned)).matches()

        where:
        divergence                              | change
        'holds a rule of its own'               | { String it -> it + "grep -c '^### Requirement' openspec/specs/*/spec.md && exit 1\n" }
        'runs a second command before the call' | { String it -> 'set -e\n' + it }
        'substitutes a command into its echo'   | { String it -> it.replace('split the requirement', '$(ls) split the requirement') }
        'drops the rule file from its message'  | { String it -> it.replace('.claude/rules/delta-specs.md', 'the rules') }
        'chains a command onto the script'      | { String it -> it.replace('"$change" 2>&1', '"$change"; true 2>&1') }
        'alters the preamble naming the change' | { String it -> it.replace('head -1', 'sh') }
    }

    def "D6: no stage or command file runs openspec archive besides the archive's own files"() {
        given:
        Map<String, Path> roots = [
            gnomish : Path.of(GradleRunnerSupport.requiredProperty('gnomish.gnomishDir')),
            commands: Path.of(GradleRunnerSupport.requiredProperty('gnomish.claudeCommandsDir')),
        ]
        Map<String, Boolean> spelled = roots.collectMany { String name, Path root ->
            Files.walk(root).withCloseable { walk ->
                walk.filter { Files.isRegularFile(it) }.toList()
            }.collect { Path file ->
                ["${name}/${root.relativize(file)}".toString(), file.getText('UTF-8').contains('openspec archive')]
            }
        }.collectEntries()

        expect: 'every allowlisted file is reached and still has the archive as its subject'
        ARCHIVE_OWNERS.keySet().every { spelled[it] }

        and: 'no other file spells the archive command'
        spelled.findAll { it.value }.keySet() == ARCHIVE_OWNERS.keySet()
    }

    private static List<String> verifyCommands() {
        File manifest = new File(GradleRunnerSupport.requiredProperty('gnomish.fixArtifactsStage'))
        Map<String, Object> stage = new Yaml().load(manifest.getText('UTF-8')) as Map<String, Object>
        (stage.verify as List<Map<String, Object>>).findAll { it.type == 'command' }.collect { it.command as String }
    }
}
