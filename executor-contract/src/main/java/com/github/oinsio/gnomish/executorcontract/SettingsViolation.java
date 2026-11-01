package com.github.oinsio.gnomish.executorcontract;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * One way a law's settings break the schema an executor's describe declared (FR9, UX1 of
 * define-executor-contract).
 *
 * @param path where inside the settings: property names joined by {@code .}, an array index as
 *     {@code [n]}, the property a rule names last — {@code maxTurn} for an unknown property, the
 *     missing one for {@code required}; empty when the violation is the settings mapping as a whole
 * @param rule the schema keyword that failed ({@code additionalProperties}, {@code type}, …);
 *     never blank
 * @param explanation the validator's description of the violation, which quotes the program's schema;
 *     never null
 */
public record SettingsViolation(String path, String rule, UntrustedText explanation) {}
