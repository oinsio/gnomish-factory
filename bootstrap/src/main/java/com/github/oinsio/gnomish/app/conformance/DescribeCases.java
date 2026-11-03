package com.github.oinsio.gnomish.app.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.executorcontract.SettingsJudgement;
import com.github.oinsio.gnomish.executorcontract.SettingsSchema;
import com.github.oinsio.gnomish.executorcontract.SettingsViolation;
import java.util.Map;
import org.jspecify.annotations.NullMarked;

/**
 * The two cases the describe answer decides (FR12, FR24 of define-executor-contract; design D19):
 * {@code describe} and {@code settings-schema}. The describe case fails on the first breach of the
 * describe schema the wire observation found, although the factory's lenient reader may have read
 * the answer; otherwise on the factory's refusal of the answer, unless that refusal is the settings
 * schema's. The settings-schema case asks the contract's one settings judge ({@link SettingsSchema})
 * whether the schema the document declares is one the factory can use and accepts the settings the
 * kit sends — the judgement the describe preflight made — and quotes the preflight's own words when
 * it refused. The judge reads the document as written, so a refused answer is still attributed to the
 * right case.
 *
 * <p>Implements FR9, FR12, FR24 of define-executor-contract.
 */
@NullMarked
final class DescribeCases {

    private static final int EXPLANATION_CAP_CHARS = 300;

    private DescribeCases() {}

    /**
     * The settings-schema case.
     *
     * @param described what describing the candidate yielded; never null
     * @param settings the settings the candidate's declaration carries; never null
     * @return the case's verdict; never null
     */
    static CaseVerdict settings(KitSubject.Described described, Map<String, ?> settings) {
        JsonNode document = described.document();
        if (document == null || !document.isObject()) {
            return new CaseVerdict.NotApplicable(
                    "the candidate wrote no describe document to read a settings schema from");
        }
        return switch (SettingsSchema.judge(document.path("settingsSchema"), settings)) {
            case SettingsJudgement.Accepted ignored -> new CaseVerdict.Passed();
            case SettingsJudgement.Rejected rejected -> {
                SettingsViolation first = rejected.violations().getFirst();
                yield refused(
                        described,
                        "the settings schema rejects the settings the kit sends (rule '" + first.rule() + "'): "
                                + first.explanation().excerpt(EXPLANATION_CAP_CHARS));
            }
            case SettingsJudgement.Unusable unusable ->
                refused(
                        described,
                        "the factory cannot use the settings schema: "
                                + unusable.reason().excerpt(EXPLANATION_CAP_CHARS));
        };
    }

    /**
     * The describe case.
     *
     * @param described what describing the candidate yielded; never null
     * @param settings the settings-schema case's verdict, which a refusal may be owed to; never null
     * @return the case's verdict; never null
     */
    static CaseVerdict describe(KitSubject.Described described, CaseVerdict settings) {
        if (!described.violations().isEmpty()) {
            return new CaseVerdict.Failed(described.violations().getFirst().detail());
        }
        if (described.answer() != null || settings instanceof CaseVerdict.Failed) {
            return new CaseVerdict.Passed();
        }
        return new CaseVerdict.Failed(
                described.refusals().isEmpty()
                        ? "the factory obtained no describe answer"
                        : described.refusals().getFirst());
    }

    private static CaseVerdict refused(KitSubject.Described described, String own) {
        return new CaseVerdict.Failed(
                described.refusals().isEmpty() ? own : described.refusals().getFirst());
    }
}
