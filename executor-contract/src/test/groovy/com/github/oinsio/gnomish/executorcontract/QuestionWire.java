package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;

/**
 * A wire record of the decision file's shape, standing in for the consumers' own: a required
 * carrier field, a list of carriers and an optional one, null when absent (FR6 of define-executor-contract).
 */
record QuestionWire(
        @JsonProperty(required = true) UntrustedText question,
        List<UntrustedText> options,
        UntrustedText note) {

    QuestionWire {
        if (options != null && options.size() == 1) {
            throw new IllegalArgumentException("one option is no choice");
        }
    }
}
