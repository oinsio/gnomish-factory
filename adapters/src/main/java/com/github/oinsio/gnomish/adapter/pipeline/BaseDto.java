package com.github.oinsio.gnomish.adapter.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code base} block of the {@code task-branch} section of {@code config.yaml} (FR1 of
 * add-base-ref-resolution): which refs a task may branch from, and which one to take when nobody
 * named one.
 *
 * <p>Wire shape:
 *
 * <pre>{@code
 * task-branch:
 *   base:
 *     type: patterns
 *     default: main
 *     allowed:
 *       - pattern: main
 *       - pattern: release/*
 *         role: release
 * }</pre>
 *
 * <p>{@code type} is a discriminator with exactly one value in this version, and it exists so that a
 * later selection mechanism arrives as a new value rather than as a schema break. It is not a
 * Jackson polymorphic discriminator: with one shape there is nothing to dispatch on, so an unknown
 * value is a located error the mapper reports (task 2.1) rather than a parse failure that would
 * short-circuit the rest of {@code config.yaml}.
 *
 * <p>Every field is nullable at the wire level so an omitted key is carried as {@code null} for the
 * mapper to default or report, rather than failing to deserialize. The section holds no
 * tracker-specific selection rule — how a task names its base is the tracker adapter's own
 * configuration ({@code tracker.<type>.designators}, design D5) — so a stray {@code select:} key
 * here is an unknown field, reported by {@link StructuralParse} like any other. The earlier draft
 * spelling ({@code menu}) is no alias for {@code allowed} and is refused the same way (D16).
 *
 * <p>Implements FR1, UX1 of add-base-ref-resolution.
 *
 * @param type the selection-mechanism discriminator, or {@code null} when omitted — the mapper
 *     defaults it to {@code patterns}
 * @param defaultRef the ref to branch from when nobody named one, or {@code null} when omitted
 * @param allowed the allowed bases in declaration order, or {@code null} when the key is omitted
 *     (the same as an empty list: no per-task selection is accepted)
 */
public record BaseDto(
        @Nullable String type,
        @Nullable @JsonProperty("default") String defaultRef,
        @Nullable List<AllowedBaseDto> allowed) {}
