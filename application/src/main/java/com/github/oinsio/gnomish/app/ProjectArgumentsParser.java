package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.ProjectArguments.Verb;
import com.github.oinsio.gnomish.app.project.ProjectName;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;

/**
 * Parses {@code gnomish project <verb> ...} into a {@link ProjectArguments}: {@code add <name>
 * [--dir=<clone>]}, {@code list}, {@code show [<name>]}. The one option is {@code --dir}; an
 * unknown option is refused first, before the verb is read (FR8 of fix-operator-blockers). {@code
 * add} is the one verb that resolves {@code --dir} itself: it registers a path rather than looking
 * one up (design D9 of add-project-registry).
 *
 * <p>Implements FR2, FR4 of add-project-registry; FR7, FR8 of fix-operator-blockers (through {@link
 * ArgumentsParsingSupport}).
 */
final class ProjectArgumentsParser {

    private static final String PROJECT_TOKEN = "project";
    private static final String DIR = "dir";
    private static final List<String> ACCEPTED = List.of(DIR);
    private static final String FORMS =
            "gnomish project add <name> --dir=<clone>, gnomish project list, or gnomish project show [<name>]";

    /**
     * @param args the raw application arguments, including the leading {@code project} token
     * @return the validated verb and its operands
     * @throws UsageException if an option is unknown, the verb is missing or unknown, the operand
     *     count does not fit the verb, the project name is invalid or {@code --dir} is malformed
     */
    ProjectArguments parse(ApplicationArguments args) {
        ArgumentsParsingSupport.rejectUnknownOptions(args, PROJECT_TOKEN, ACCEPTED, Map.of());
        List<String> positionals = ArgumentsParsingSupport.allPositionalsAfterSubcommand(args, PROJECT_TOKEN);
        if (positionals.isEmpty()) {
            throw new UsageException("a project verb is required: " + FORMS);
        }
        List<String> operands = positionals.subList(1, positionals.size());
        return switch (positionals.getFirst()) {
            case "add" ->
                new ProjectArguments(
                        Verb.ADD, name(exactlyOne(operands, "add")), ArgumentsParsingSupport.projectDir(args));
            case "list" -> {
                atMost(0, operands, "list");
                yield new ProjectArguments(Verb.LIST, null, null);
            }
            case "show" -> {
                atMost(1, operands, "show");
                yield new ProjectArguments(Verb.SHOW, operands.isEmpty() ? null : name(operands.getFirst()), null);
            }
            default ->
                throw new UsageException(
                        "'" + positionals.getFirst() + "' is not a project verb: accepted forms are " + FORMS);
        };
    }

    private static String exactlyOne(List<String> operands, String verb) {
        if (operands.isEmpty()) {
            throw new UsageException("a project name is required: gnomish project " + verb + " <name> --dir=<clone>");
        }
        atMost(1, operands, verb);
        return operands.getFirst();
    }

    private static void atMost(int count, List<String> operands, String verb) {
        if (operands.size() > count) {
            throw new UsageException("unexpected argument '" + operands.get(count) + "' for 'gnomish project " + verb
                    + "'; accepted forms are " + FORMS);
        }
    }

    private static ProjectName name(String value) {
        try {
            return new ProjectName(value);
        } catch (IllegalArgumentException e) {
            throw new UsageException(String.valueOf(e.getMessage()));
        }
    }
}
