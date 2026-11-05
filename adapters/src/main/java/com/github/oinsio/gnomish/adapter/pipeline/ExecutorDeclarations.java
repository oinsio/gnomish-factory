package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration;
import com.github.oinsio.gnomish.executorcontract.ExecutorName;
import com.github.oinsio.gnomish.executorcontract.VariableNames;
import com.github.oinsio.gnomish.sandbox.EgressEntries;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The structural tier and the mapping of the {@code config.yaml} {@code executors:} section —
 * phase (a) of design D5 of define-executor-contract, the only check the loader runs on it: no
 * process is spawned and no describe is asked, since the law is bound before any execution
 * environment exists. Each problem is a located {@link ConfigError} naming the declaration and
 * the field (UX1); whether a stage's {@code executor.name} names a declaration is the domain
 * {@code ExecutorReferenceRule}'s concern, and the settings are the describe schema's.
 *
 * <p>Every grammar is judged by its one owner: the name by {@link ExecutorName#isName}, a
 * credential by {@link VariableNames#isVariableName} (the grammar describe's credentials are held
 * to), an egress host by {@link EgressEntries#isEntry} (the grammar the egress guard renders), so a
 * declaration the law accepts is one the later readers accept.
 *
 * <p>Implements FR9, FR10, UX1 of define-executor-contract.
 */
final class ExecutorDeclarations {

    private static final String CONFIG = "config.yaml";

    /**
     * The declarable type wire values: {@code program} only. A built-in executor ({@code command},
     * design D6 of add-command-executor) is registered at wiring and reached by stage type, never
     * declared.
     */
    private static final List<String> TYPES = List.of("program");

    private ExecutorDeclarations() {}

    /**
     * Checks the section's shape, declaration by declaration in declaration order.
     *
     * @param executors the parsed section, or {@code null} when absent
     * @return every located problem; immutable, empty for an absent or sound section
     */
    static List<ConfigError> check(@Nullable Map<String, ExecutorDeclarationDto> executors) {
        if (executors == null) {
            return List.of();
        }
        List<ConfigError> errors = new ArrayList<>();
        executors.forEach((name, dto) -> checkOne(name, orEmpty(dto), errors));
        return List.copyOf(errors);
    }

    /**
     * Maps the section to its domain declarations, in declaration order. Total on any parsed
     * section: a missing field maps to empty, so a shape problem {@link #check} reported never
     * becomes an exception here; a settings key with no value is a located error appended to
     * {@code errors} by {@link OpaqueSettings#copy}.
     *
     * @param executors the parsed section, or {@code null} when absent
     * @param errors the caller's mutable error accumulator
     * @return the declarations; immutable, empty for an absent section
     */
    static List<ExecutorDeclaration> map(
            @Nullable Map<String, ExecutorDeclarationDto> executors, List<ConfigError> errors) {
        if (executors == null) {
            return List.of();
        }
        List<ExecutorDeclaration> mapped = new ArrayList<>();
        executors.forEach((name, raw) -> {
            ExecutorDeclarationDto dto = orEmpty(raw);
            // `program` is the only declarable type; check() reported any other value.
            mapped.add(new ExecutorDeclaration(
                    name,
                    ExecutorDeclaration.Type.PROGRAM,
                    strings(dto.program()),
                    OpaqueSettings.copy(CONFIG, "executors." + name + ".settings", dto.settings(), errors),
                    strings(dto.credentials()),
                    strings(dto.egress())));
        });
        return List.copyOf(mapped);
    }

    private static void checkOne(String name, ExecutorDeclarationDto dto, List<ConfigError> errors) {
        String where = "executors." + name;
        if (!ExecutorName.isName(name)) {
            errors.add(new ConfigError(
                    CONFIG,
                    where,
                    "invalid executor name '%s'; a name matches %s".formatted(name, ExecutorName.grammar())));
        }
        String type = dto.type();
        if (type == null) {
            errors.add(new ConfigError(CONFIG, where + ".type", "missing required field 'type'"));
        } else if (!TYPES.contains(type)) {
            errors.add(new ConfigError(
                    CONFIG,
                    where + ".type",
                    "unknown executor type '%s'; known executor types are %s"
                            .formatted(type, String.join(", ", TYPES))));
        } else {
            checkArgv(where, dto.program(), errors);
        }
        checkCredentials(where, dto.credentials(), errors);
        checkEgress(where, dto.egress(), errors);
    }

    private static void checkArgv(String where, @Nullable List<String> argv, List<ConfigError> errors) {
        if (argv == null || argv.isEmpty()) {
            errors.add(new ConfigError(
                    CONFIG,
                    where + ".program",
                    "missing required field 'program'; a program declaration names its argv, the program first"));
            return;
        }
        for (int i = 0; i < argv.size(); i++) {
            if (isBlank(argv.get(i))) {
                errors.add(new ConfigError(
                        CONFIG, "%s.program[%d]".formatted(where, i), "blank argv entry; every argv entry is a word"));
            }
        }
    }

    private static void checkCredentials(String where, @Nullable List<String> names, List<ConfigError> errors) {
        List<String> values = strings(names);
        for (int i = 0; i < values.size(); i++) {
            if (!VariableNames.isVariableName(values.get(i))) {
                errors.add(new ConfigError(
                        CONFIG,
                        "%s.credentials[%d]".formatted(where, i),
                        "invalid credential name '%s'; a credential is an environment variable name matching %s"
                                .formatted(values.get(i), VariableNames.grammar())));
            }
        }
    }

    private static void checkEgress(String where, @Nullable List<String> hosts, List<ConfigError> errors) {
        List<String> values = strings(hosts);
        for (int i = 0; i < values.size(); i++) {
            if (!EgressEntries.isEntry(values.get(i))) {
                errors.add(new ConfigError(
                        CONFIG,
                        "%s.egress[%d]".formatted(where, i),
                        "invalid egress host '%s'; an egress entry is a host name, an IP literal or a *.suffix wildcard"
                                .formatted(values.get(i))));
            }
        }
    }

    private static ExecutorDeclarationDto orEmpty(@Nullable ExecutorDeclarationDto dto) {
        return dto == null ? new ExecutorDeclarationDto(null, null, null, null, null) : dto;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    /** A copy with each absent element as blank; an absent list maps to empty. */
    private static List<String> strings(@Nullable List<String> values) {
        if (values == null) {
            return List.of();
        }
        List<String> copy = new ArrayList<>();
        for (String value : values) {
            copy.add(value == null ? "" : value);
        }
        return copy;
    }
}
