package com.github.oinsio.gnomish.buildlogic

import java.util.regex.Matcher
import java.util.regex.Pattern
import org.yaml.snakeyaml.Yaml

/**
 * The spec-contract launcher a stage manifest's {@code verify} list carries (design D5 of
 * tighten-spec-contract), read with a YAML parser: the one {@code command} check that names
 * {@code checkSpecContract}, reduced to the Gradle arguments it passes.
 *
 * <p>The check's whole text is pinned to one shape — optionally the fixed {@link #CHANGE_PREAMBLE}
 * that names the task's change, then run the task, and on failure print the tail of its output,
 * one {@code echo} of literal text and {@code exit 1} — so the launcher can hold no rule: the only
 * command it runs besides the preamble's is {@code ./gradlew}, the echo admits no substitution,
 * every Gradle argument besides the task is an option, and the one substitution an argument may
 * carry is the preamble's change name, in the last argument {@link #CHANGE_ARGUMENT}. Whether
 * those options leave the verdict intact is not judged here: the functional spec runs them
 * against the fixture tree, the change name bound by {@link #bind}.
 */
final class StageSpecContractCommand {

    static final String TASK = 'checkSpecContract'

    /** The change filter a launcher may pass, bound to the change its preamble names. */
    static final String CHANGE_ARGUMENT = '-PspecContractChange="$change"'

    /** The lines naming the task's change, exactly as the stage's other checks name it. */
    static final String CHANGE_PREAMBLE = '''\
# The change this task works on, named as the checks above name it.
branch=$(git branch --show-current)
case "$branch" in gnomish/*) task=${branch#gnomish/} ;; *) task=local ;; esac
change=$(sed -n 's/^Change:[[:space:]]*//p' temporary-docs/gnomish/$task/change.md | head -1)
'''

    private static final Pattern SHAPE = Pattern.compile(
            '^(?:' + Pattern.quote(CHANGE_PREAMBLE) + ')?'
                    + 'if ! out=\\$\\(\\./gradlew ([^()$`;&|<>"\'\\\\\\n]+?(?: ' + Pattern.quote(CHANGE_ARGUMENT) + ')?) 2>&1\\); then\\n'
                    + '  printf \'%s\\\\n\' "\\$out" \\| tail -40\\n'
                    + '  echo "(?:[^"$`\\\\\\n]|\\\\\\$)*"\\n'
                    + '  exit 1\\n'
                    + 'fi\\n?$')

    private StageSpecContractCommand() {
    }

    /** The Gradle arguments of the launcher in the manifest at {@code path}. */
    static List<String> gradleArguments(File manifest) {
        Map<String, Object> stage = new Yaml().load(manifest.getText('UTF-8')) as Map<String, Object>
        List<String> launchers = (stage.verify as List<Map<String, Object>>)
                .findAll { it.type == 'command' && (it.command as String).contains(TASK) }
                .collect { it.command as String }
        if (launchers.size() != 1) {
            throw new IllegalStateException("${manifest}: expected one verify command naming ${TASK}, found ${launchers.size()}")
        }
        parse(launchers[0])
    }

    /** The Gradle arguments of one launcher's text; refuses any text outside the pinned shape. */
    static List<String> parse(String command) {
        Matcher shape = SHAPE.matcher(command)
        if (!shape.matches()) {
            throw new IllegalArgumentException("not a spec-contract launcher of the pinned shape:\n${command}")
        }
        List<String> arguments = shape.group(1).split(' ') as List<String>
        if (arguments.count(TASK) != 1 || arguments.any { it != TASK && !it.startsWith('-') }) {
            throw new IllegalArgumentException("a launcher runs ${TASK} and nothing else, given: ${arguments}")
        }
        if (arguments.contains(CHANGE_ARGUMENT) && !command.startsWith(CHANGE_PREAMBLE)) {
            throw new IllegalArgumentException("a launcher passes ${CHANGE_ARGUMENT} only after the preamble naming the change")
        }
        arguments
    }

    /** The arguments as the shell passes them once the preamble has named {@code change}. */
    static List<String> bind(List<String> arguments, String change) {
        arguments.collect { it == CHANGE_ARGUMENT ? "-PspecContractChange=${change}".toString() : it }
    }
}
