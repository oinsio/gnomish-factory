package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * An executor's answer to {@code describe}, read by the describe preflight before the first round.
 * Wire shape ({@code executor-describe.schema.json}): {@code {"protocolVersion", "version",
 * "roles", "capabilities", "settingsSchema", "credentials", "egress", "configDirVariable"?,
 * "policyDisablingFlags", "enforcementLevel", "reportsUsage"}}.
 *
 * <p>Unknown tokens follow their enums' arms: an unknown role or capability is dropped from its list
 * (so a use site that needs the role is refused as undeclared), an unknown enforcement level reads
 * as {@link EnforcementLevel#PROMPT_ONLY}. Credential and configuration-directory names must be
 * environment variable names.
 *
 * <p>The executor's {@code version} is free text shown to an operator, so it is a carrier (NFR-S2).
 * The credential names and the configuration-directory name are held to the environment-variable
 * grammar and used as names, so they stay strings; the egress hosts and the policy-disabling flags
 * are identifiers handed on to the egress guard and to the executor's own argv, never shown as
 * prose, and stay strings too. The settings schema node is shared, not copied: no holder mutates it.
 *
 * <p>Implements FR4, FR13, NFR-S2 of define-executor-contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Describe(
        @JsonProperty(required = true) int protocolVersion,
        UntrustedText version,
        @JsonSetter(contentNulls = Nulls.SKIP) List<Role> roles,
        @JsonSetter(contentNulls = Nulls.SKIP) List<Capability> capabilities,
        ObjectNode settingsSchema,
        List<String> credentials,
        List<String> egress,
        @Nullable String configDirVariable,
        List<String> policyDisablingFlags,
        EnforcementLevel enforcementLevel,
        @JsonProperty(required = true) boolean reportsUsage) {

    /**
     * The one protocol version the factory speaks (FR4 of define-executor-contract): the describe
     * preflight (task 6.2) refuses an executor describing any other, so every round a described
     * executor runs is spoken in this version, and the round's log line names it (NFR-O2).
     */
    public static final int SPOKEN_PROTOCOL_VERSION = 1;

    /** Refuses a version below 1, a blank executor version or host, and a malformed variable name. */
    public Describe {
        if (protocolVersion < 1) {
            throw new IllegalArgumentException("protocolVersion must be at least 1, was " + protocolVersion);
        }
        WireChecks.nonBlank(version, "version");
        roles = List.copyOf(roles);
        capabilities = List.copyOf(capabilities);
        credentials = List.copyOf(credentials);
        WireChecks.variableNameEach(credentials, "credentials[]");
        egress = List.copyOf(egress);
        WireChecks.nonBlankEach(egress, "egress[]");
        if (configDirVariable != null) {
            WireChecks.variableName(configDirVariable, "configDirVariable");
        }
        policyDisablingFlags = List.copyOf(policyDisablingFlags);
    }
}
