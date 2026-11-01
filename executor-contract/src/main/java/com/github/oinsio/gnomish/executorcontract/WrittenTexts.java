package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The string fields of a written answer document as carriers: {@link WrittenNodes}' typed reads,
 * each value minted in the family of the document's writer the moment it is lifted out (FR6, NFR-S2
 * of define-executor-contract). A class, not a record: the mutation engine cannot redefine a
 * record's methods (testing.md, JVMTI limit).
 *
 * <p>Implements FR3, FR6, NFR-S2 of define-executor-contract.
 */
final class WrittenTexts {

    private final Function<String, UntrustedText> mint;

    WrittenTexts(Function<String, UntrustedText> mint) {
        this.mint = mint;
    }

    /** The string {@code field} of {@code node}; refuses an absent or non-string one. */
    UntrustedText text(JsonNode node, String field, String where) {
        return mint.apply(WrittenNodes.text(node, field, where));
    }

    /** The string {@code field} of {@code node}, or {@code null} when absent; refuses a non-string. */
    @Nullable
    UntrustedText optionalText(JsonNode node, String field, String where) {
        String value = WrittenNodes.optionalText(node, field, where);
        return value == null ? null : mint.apply(value);
    }

    /** The string array {@code field} of {@code node}, empty when absent; refuses anything else. */
    List<UntrustedText> texts(JsonNode node, String field, String where) {
        return WrittenNodes.texts(node, field, where).stream().map(mint).toList();
    }
}
