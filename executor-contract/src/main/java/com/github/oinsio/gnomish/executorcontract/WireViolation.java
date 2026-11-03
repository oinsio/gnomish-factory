package com.github.oinsio.gnomish.executorcontract;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * One way a document an executor wrote, or a request the factory rendered, breaks its schema file
 * (FR24 of define-executor-contract): what the conformance kit reports for a case, on the raw text,
 * whatever the lenient readers would have made of it.
 *
 * @param path where inside the document, in the form of {@link SettingsViolation#path()}: property
 *     names joined by {@code .}, an array index as {@code [n]}, the property a rule names last;
 *     empty when the violation is the document as a whole
 * @param keyword the schema keyword that failed ({@code enum}, {@code required}, …), or {@link
 *     #NOT_JSON} when the text is no single JSON value; never blank
 * @param message the validator's description of the violation, which quotes the document; never null
 */
public record WireViolation(String path, String keyword, UntrustedText message) {

    /** The keyword of a text that is no single JSON value, so no schema keyword could judge it. */
    public static final String NOT_JSON = "json";
}
