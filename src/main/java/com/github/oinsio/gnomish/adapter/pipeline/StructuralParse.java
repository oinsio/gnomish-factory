package com.github.oinsio.gnomish.adapter.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import java.util.List;

/**
 * Parses a single {@code .gnomish/} YAML file's text into its adapter DTO,
 * converting every Jackson content problem into one located {@link ConfigError}
 * for that file rather than letting it escape as an exception (design D2/D3).
 * This is the structural capture step of task 5.2 (FR5): malformed YAML, a type
 * mismatch, an unrecognized field, and an unknown or missing {@code type}/
 * {@code kind} discriminator are all reported as data.
 *
 * <p>Per-file short-circuit (design D6): a file that will not parse at all
 * returns {@link Failed}, so the caller (the loader, task 6.5) skips that file's
 * downstream shape and semantic checks while other files still report their own
 * problems — aggregation degrades gracefully rather than collapsing.
 *
 * <p>Scope: this class parses already-read text; genuine I/O faults (an
 * unreadable file) are the loader's concern (task 6.1) and stay exceptions
 * (FR8). Post-parse shape checks (required fields, raw-string enums) are
 * {@link StructuralValidation}; DTO&rarr;domain mapping is task 5.3.
 *
 * <p>Implements FR5 of load-pipeline-config.
 */
public final class StructuralParse {

    private StructuralParse() {}

    /** The outcome of parsing one file: either the DTO or a single located error. */
    public sealed interface Result<T> permits Ok, Failed {}

    /**
     * A successful parse carrying the deserialized DTO.
     *
     * @param value the deserialized DTO
     * @param <T> the DTO type
     */
    public record Ok<T>(T value) implements Result<T> {}

    /**
     * A failed parse carrying exactly one located error (per-file short-circuit).
     *
     * @param errors the single-element located error list for this file
     * @param <T> the DTO type this parse targeted
     */
    public record Failed<T>(List<ConfigError> errors) implements Result<T> {
        public Failed {
            errors = List.copyOf(errors);
        }
    }

    /**
     * Parses {@code text} into {@code type}, returning {@link Ok} on success or a
     * {@link Failed} carrying one located {@link ConfigError} for {@code file}.
     *
     * <p>Implements FR5 of load-pipeline-config.
     *
     * @param file the file's path relative to the {@code .gnomish/} root, stamped
     *     into any error (NFR-O1)
     * @param text the file's YAML text
     * @param type the target DTO class
     * @param <T> the DTO type
     * @return {@link Ok} with the DTO, or {@link Failed} with one located error
     */
    public static <T> Result<T> parse(String file, String text, Class<T> type) {
        try {
            return new Ok<>(PipelineYaml.mapper().readValue(text, type));
        } catch (InvalidTypeIdException e) {
            return failed(discriminatorError(file, e));
        } catch (UnrecognizedPropertyException e) {
            return failed(
                    new ConfigError(file, e.getPropertyName(), "unknown field '%s'".formatted(e.getPropertyName())));
        } catch (MismatchedInputException e) {
            return failed(mismatchError(file, e));
        } catch (JsonProcessingException e) {
            return failed(
                    new ConfigError(file, file, "malformed YAML: the file is not well-formed and cannot be parsed"));
        }
    }

    private static <T> Result<T> failed(ConfigError error) {
        return new Failed<>(List.of(error));
    }

    /** An unknown or missing {@code type}/{@code kind} discriminator on a sealed DTO family. */
    private static ConfigError discriminatorError(String file, InvalidTypeIdException e) {
        Discriminator d = Discriminator.of(e);
        String where = locate(file, e);
        String typeId = e.getTypeId();
        if (typeId == null) {
            return new ConfigError(
                    file,
                    where,
                    "missing required %s '%s'; known %ss are %s".formatted(d.family, d.property, d.property, d.known));
        }
        return new ConfigError(
                file,
                where,
                "unknown %s %s '%s'; known %ss are %s".formatted(d.family, d.property, typeId, d.property, d.known));
    }

    /** The named DTO family a bad {@code type}/{@code kind} discriminator belongs to. */
    private enum Discriminator {
        VERIFY("verify check", "type", "builtin, command, external, judge"),
        INPUT("input", "kind", "internal, source");

        private final String family;
        private final String property;
        private final String known;

        Discriminator(String family, String property, String known) {
            this.family = family;
            this.property = property;
            this.known = known;
        }

        private static Discriminator of(InvalidTypeIdException e) {
            String base = e.getBaseType().toCanonical();
            return base.endsWith("VerifyCheckDto") ? VERIFY : INPUT;
        }
    }

    /** A type mismatch: a scalar where a list/object was expected, or similar. */
    private static ConfigError mismatchError(String file, MismatchedInputException e) {
        String where = locate(file, e);
        return new ConfigError(file, where, "type mismatch: '%s' has the wrong YAML type".formatted(where));
    }

    /**
     * Derives a compact field locator from Jackson's path reference, e.g.
     * {@code StageDto["verify"]->java.util.ArrayList[0]} &rarr; {@code verify[0]}
     * and {@code PipelineDto["stages"]} &rarr; {@code stages}. Falls back to the
     * file itself when Jackson gives no usable path (a root-level problem).
     */
    private static String locate(String file, MismatchedInputException e) {
        StringBuilder locator = new StringBuilder();
        for (var ref : e.getPath()) {
            if (ref.getFieldName() != null) {
                if (!locator.isEmpty()) {
                    locator.append('.');
                }
                locator.append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                locator.append('[').append(ref.getIndex()).append(']');
            }
        }
        return locator.isEmpty() ? file : locator.toString();
    }
}
