package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.baseref.AllowedBase;
import com.github.oinsio.gnomish.baseref.AllowedBases;
import com.github.oinsio.gnomish.baseref.BaseDefinition;
import com.github.oinsio.gnomish.baseref.BasePattern;
import com.github.oinsio.gnomish.baseref.BranchRole;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Validates the {@code task-branch.base} section of {@code config.yaml} and maps it into the {@code :baseref}
 * value types (FR1, UX1 of add-base-ref-resolution, design D2).
 *
 * <p>The patterns compile <em>here</em>, at load: a malformed pattern is then a located error an
 * author sees beside every other one, rather than a surprise at claim time on some later task. That
 * is also why the mapped result is a compiled {@link AllowedBases} rather than the raw strings — nothing
 * downstream re-parses what this pass has already graded.
 *
 * <p>Aggregation, not short-circuit (UX1): each problem is appended to the shared error list and the
 * pass keeps going, so one load reports the unknown type, the bad pattern and the unknown role
 * together. The one dependency between checks is the default: it is held against the allowed
 * bases only when they themselves graded clean, since a partially-compiled list would fail a
 * default that is actually fine.
 *
 * <p>Implements FR1, UX1 of add-base-ref-resolution.
 */
final class BaseConfigMapper {

    /** The one selection mechanism this version implements; see {@link BaseDto} on why it is named. */
    private static final String PATTERNS_TYPE = "patterns";

    /** FR1: the {@code config.yaml} location stamped onto every problem in the section. */
    private static final String FILE = "config.yaml";

    private BaseConfigMapper() {}

    /**
     * Maps the {@code task-branch.base} section, appending every located problem to {@code errors}.
     *
     * @param base the parsed section, or {@code null} when {@code config.yaml} declares none — which
     *     is valid, and maps to {@link BaseDefinition#none()}
     * @param errors the shared single-pass error list; appended to, never read
     * @return the compiled definition; a definition built from the entries that <em>did</em> grade
     *     clean when some did not, since the load fails on the errors anyway and a half-compiled
     *     list is never consulted
     */
    static BaseDefinition map(@Nullable BaseDto base, List<ConfigError> errors) {
        if (base == null) {
            return BaseDefinition.none();
        }
        checkType(base.type(), errors);
        int before = errors.size();
        AllowedBases allowedBases = AllowedBases.of(entries(base.allowed(), errors));
        boolean allowedBasesClean = errors.size() == before;
        checkDefault(base.defaultRef(), allowedBases, allowedBasesClean, errors);
        return new BaseDefinition(allowedBases, base.defaultRef());
    }

    /** The discriminator: absent means {@code patterns}, anything else is a located error. */
    private static void checkType(@Nullable String type, List<ConfigError> errors) {
        if (type != null && !PATTERNS_TYPE.equals(type)) {
            errors.add(new ConfigError(
                    FILE,
                    "task-branch.base.type",
                    "unknown base type '%s'; known types are %s".formatted(type, PATTERNS_TYPE)));
        }
    }

    /** Compiles each declared entry, reporting a missing pattern, a bad pattern and an unknown role. */
    private static List<AllowedBase> entries(@Nullable List<AllowedBaseDto> allowed, List<ConfigError> errors) {
        if (allowed == null) {
            return List.of();
        }
        List<AllowedBase> compiled = new ArrayList<>();
        for (int i = 0; i < allowed.size(); i++) {
            String where = "task-branch.base.allowed[%d]".formatted(i);
            AllowedBaseDto entry = allowed.get(i);
            if (entry == null) {
                errors.add(new ConfigError(FILE, where, "allowed base is empty"));
                continue;
            }
            BasePattern pattern = pattern(where, entry.pattern(), errors);
            BranchRole role = role(where, entry.role(), errors);
            if (pattern != null && role != null) {
                compiled.add(new AllowedBase(pattern, role));
            }
        }
        return compiled;
    }

    /** The entry's pattern, or null when it is missing or will not compile. */
    private static @Nullable BasePattern pattern(String where, @Nullable String source, List<ConfigError> errors) {
        if (source == null) {
            errors.add(new ConfigError(FILE, where + ".pattern", "missing required allowed-base pattern"));
            return null;
        }
        String violation = BasePattern.violation(source).orElse(null);
        if (violation != null) {
            errors.add(new ConfigError(
                    FILE, where + ".pattern", "invalid allowed-base pattern '%s': %s".formatted(source, violation)));
            return null;
        }
        return BasePattern.compile(source);
    }

    /** The entry's role, defaulting to development when omitted, or null when the token is unknown. */
    private static @Nullable BranchRole role(String where, @Nullable String declared, List<ConfigError> errors) {
        if (declared == null) {
            return BranchRole.defaultRole();
        }
        for (BranchRole role : BranchRole.values()) {
            if (wireToken(role).equals(declared)) {
                return role;
            }
        }
        errors.add(new ConfigError(
                FILE,
                where + ".role",
                "unknown allowed-base role '%s'; known roles are %s".formatted(declared, knownRoles())));
        return null;
    }

    /**
     * A role's token as an author writes it: the constant, lower-cased. Derived rather than tabled,
     * so a role added to the vocabulary cannot be one the loader silently refuses.
     */
    private static String wireToken(BranchRole role) {
        return role.name().toLowerCase(Locale.ROOT);
    }

    /** The accepted role tokens, in declaration order, for the error an author reads. */
    private static String knownRoles() {
        return Arrays.stream(BranchRole.values())
                .map(BaseConfigMapper::wireToken)
                .collect(Collectors.joining(", "));
    }

    /**
     * Holds a declared default against the declared allowed bases. A project that allows none accepts
     * no per-task selection at all, so its default is the only base there is and nothing bounds it.
     */
    private static void checkDefault(
            @Nullable String declared, AllowedBases allowedBases, boolean allowedBasesClean, List<ConfigError> errors) {
        if (declared == null || allowedBases.isEmpty() || !allowedBasesClean) {
            return;
        }
        if (allowedBases.match(declared).isEmpty()) {
            errors.add(new ConfigError(
                    FILE,
                    "task-branch.base.default",
                    "default '%s' matches no task-branch.base.allowed pattern; the allowed bases are %s"
                            .formatted(declared, allowedBases.describe())));
        }
    }
}
