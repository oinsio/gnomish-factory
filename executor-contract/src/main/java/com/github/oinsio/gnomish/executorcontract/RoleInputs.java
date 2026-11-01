package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The inputs of a request that differ by role: instructions for {@link Role#TRANSFORM}, criteria
 * for {@link Role#VERDICT}, a question and at least two options for {@link Role#DECISION};
 * {@link Role#EFFECT} takes none in this version. Which inputs a role requires is the role's to say ({@link
 * Role#accepts}). Wire shape: {@code {"instructions"?, "criteria"?, "question"?, "options"?}}.
 *
 * <p>Implements FR1, FR11 of define-executor-contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoleInputs(
        @Nullable String instructions,
        @Nullable String criteria,
        @Nullable String question,

        @JsonInclude(JsonInclude.Include.NON_EMPTY) @JsonSetter(nulls = Nulls.AS_EMPTY)
        List<String> options) {

    /** Refuses a blank input or a blank option. */
    public RoleInputs {
        WireChecks.nonBlankOrAbsent(instructions, "roleInputs.instructions");
        WireChecks.nonBlankOrAbsent(criteria, "roleInputs.criteria");
        WireChecks.nonBlankOrAbsent(question, "roleInputs.question");
        options = List.copyOf(options);
        WireChecks.nonBlankEach(options, "roleInputs.options[]");
    }

    // @DoNotMutate, JVMTI redefinition limit (testing.md): PIT's "replaced return value with null"
    // mutant of this factory ends its minion with "class redefinition failed: attempted to change the
    // class ... Record ... attribute" (RUN_ERROR, hcoles/pitest#1285), so no test can observe it. The
    // factory holds no decision, and every spec that builds a request through it covers it.
    /** The inputs of a {@code transform} run. */
    @DoNotMutate
    public static RoleInputs transform(String instructions) {
        return new RoleInputs(instructions, null, null, List.of());
    }

    // @DoNotMutate, JVMTI redefinition limit (testing.md): PIT's "replaced return value with null"
    // mutant of this factory ends its minion with "class redefinition failed: attempted to change the
    // class ... Record ... attribute" (RUN_ERROR, hcoles/pitest#1285), so no test can observe it. The
    // factory holds no decision, and every spec that builds a request through it covers it.
    /** The inputs of a {@code verdict} run. */
    @DoNotMutate
    public static RoleInputs verdict(String criteria) {
        return new RoleInputs(null, criteria, null, List.of());
    }

    // @DoNotMutate, JVMTI redefinition limit (testing.md): PIT's "replaced return value with null"
    // mutant of this factory ends its minion with "class redefinition failed: attempted to change the
    // class ... Record ... attribute" (RUN_ERROR, hcoles/pitest#1285), so no test can observe it. The
    // factory holds no decision, and every spec that builds a request through it covers it.
    /** The inputs of a {@code decision} run. */
    @DoNotMutate
    public static RoleInputs decision(String question, List<String> options) {
        return new RoleInputs(null, null, question, options);
    }

    /** No inputs: the {@code effect} role's. */
    public static RoleInputs none() {
        return new RoleInputs(null, null, null, List.of());
    }
}
