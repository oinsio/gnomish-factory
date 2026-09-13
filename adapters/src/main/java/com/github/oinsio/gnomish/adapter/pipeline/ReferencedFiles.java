package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.adapter.law.LawSource;
import com.github.oinsio.gnomish.adapter.law.WorkingTreeLawSource;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The loader's referenced-file existence check (task 6.3, FR6): given the
 * {@code .gnomish/} tree as a {@link LawSource} and the mapped domain stages, it
 * confirms that every non-blank referenced file — each stage's {@code instructions.md} (the Control
 * section) and every {@code judge} check's acceptance-criteria file — resolves to
 * a real regular file, relative to the law root. Each miss becomes a located
 * {@link ConfigError} (NFR-O1, UX2) on the referencing stage's manifest
 * {@code stages/<name>/stage.yaml}, naming the missing path so the fix is
 * unambiguous.
 *
 * <p><b>Adapter tier (design D6).</b> The check needs a real tree to ask, so it
 * lives in the adapter, not the pure domain. Which tree — the working tree, or the
 * law commit's own tree in git objects — is the source's business (design D12 of
 * add-base-ref-resolution): the check only classifies each reference. It only tests existence — file
 * <em>contents</em> are never read (NG7: gradeability of judge criteria is a later
 * change) and nothing is executed (NFR-S1).
 *
 * <p><b>Existence semantics.</b> A reference resolves only when it names a regular
 * file. A path that does not exist and a path that is a directory both fail — a
 * directory cannot serve as an instructions or criteria file.
 *
 * <p><b>Blank references.</b> A blank {@code instructionsRef} or {@code criteriaFile}
 * is a <em>presence</em> concern, not existence: instructions presence is
 * {@link StructuralValidation}'s (task 5.2), which already reports an absent
 * {@code instructions} field. A blank path is never resolved here — resolving one
 * against the root would falsely "exist" as the root directory. This check therefore
 * inspects only non-blank references and never double-reports presence.
 *
 * <p><b>Path traversal first (NFR-S2, task 6.4).</b> The law source classifies each
 * non-blank reference through its own traversal guard before any existence question is
 * answered. A reference that escapes the {@code .gnomish/} root — a lexical
 * {@code ../}/absolute climb, a symlink whose real target is outside, or, in a git tree
 * that has no {@code realpath} to canonicalize with, any symlink at all — comes back
 * {@link LawSource.FileStatus#REFUSED} and becomes a located
 * <em>escapes-the-configuration-root</em> error, so the outside file is never read and one
 * reference never yields both a traversal and a "does not exist" error.
 *
 * <p><b>Deterministic order (NFR-R1, UX1).</b> Stages in pipeline order; within a
 * stage the instructions error first, then judge-criteria errors in {@code verify}
 * declaration order — so every problem is fixable in a single pass.
 *
 * <p>Implements FR6 and NFR-S2 of load-pipeline-config; FR11 of
 * add-base-ref-resolution.
 */
public final class ReferencedFiles {

    private ReferencedFiles() {}

    /**
     * Checks that every non-blank referenced file of every stage exists as a
     * regular file in {@code law}.
     *
     * <p>Implements FR6 of load-pipeline-config; FR11 of add-base-ref-resolution.
     *
     * @param law the law source rooted at the {@code .gnomish/} tree, against which
     *     relative references are resolved
     * @param stages the mapped domain stages, in {@code pipeline.yaml} order
     * @return every located existence error — instructions before judge criteria
     *     within a stage, stages in pipeline order; an immutable, possibly empty list
     */
    public static List<ConfigError> check(LawSource law, List<StageDefinition> stages) {
        List<ConfigError> errors = new ArrayList<>();
        for (StageDefinition stage : stages) {
            checkStage(law, stage, errors);
        }
        return List.copyOf(errors);
    }

    /**
     * The boundary form for a caller that holds a directory: the same check over the working
     * tree rooted at {@code gnomishRoot}.
     *
     * <p>Implements FR6 of load-pipeline-config; FR11 of add-base-ref-resolution.
     *
     * @param gnomishRoot the {@code .gnomish/} directory root
     * @param stages the mapped domain stages, in {@code pipeline.yaml} order
     * @return every located existence error, in the same order
     */
    public static List<ConfigError> check(Path gnomishRoot, List<StageDefinition> stages) {
        return check(new WorkingTreeLawSource(gnomishRoot), stages);
    }

    private static void checkStage(LawSource law, StageDefinition stage, List<ConfigError> errors) {
        String manifest = "stages/%s/stage.yaml".formatted(stage.name());
        String instructionsRef = stage.instructionsRef();
        if (!instructionsRef.isBlank()) {
            checkRef(law, manifest, "instructions", instructionsRef, "instructions", errors);
        }
        List<VerifyCheck> checks = stage.verify();
        for (int index = 0; index < checks.size(); index++) {
            if (checks.get(index) instanceof VerifyCheck.Judge judge) {
                checkCriteria(law, manifest, index, judge, errors);
            }
        }
    }

    private static void checkCriteria(
            LawSource law, String manifest, int index, VerifyCheck.Judge judge, List<ConfigError> errors) {
        String criteriaFile = judge.criteriaFile();
        if (!criteriaFile.isBlank()) {
            String where = "verify[%d].criteriaFile".formatted(index);
            checkRef(law, manifest, where, criteriaFile, "acceptance-criteria", errors);
        }
    }

    /**
     * Traversal-first check of one non-blank reference (task 6.4 before 6.3): the law source
     * classifies it; a {@link LawSource.FileStatus#REFUSED} verdict yields an
     * escapes-the-root error and asks no existence question (NFR-S2), an
     * {@link LawSource.FileStatus#ABSENT} one a does-not-exist error (FR6). The two errors
     * are mutually exclusive for a given reference.
     *
     * @param kind the human phrase for the reference kind — {@code instructions} or
     *     {@code acceptance-criteria} — used to build both messages consistently (UX2)
     */
    private static void checkRef(
            LawSource law, String manifest, String where, String ref, String kind, List<ConfigError> errors) {
        switch (law.fileStatus(ref)) {
            case REFUSED ->
                errors.add(new ConfigError(
                        manifest,
                        where,
                        "referenced %s file '%s' escapes the configuration root".formatted(kind, ref)));
            case ABSENT ->
                errors.add(new ConfigError(
                        manifest, where, "referenced %s file '%s' does not exist".formatted(kind, ref)));
            case REGULAR_FILE -> {
                // The reference resolves to a law file: nothing to report.
            }
        }
    }
}
