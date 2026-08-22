package com.github.oinsio.gnomish.serveobservability.json;

import org.jspecify.annotations.Nullable;

/**
 * The JSON contract's {@code sweepAction} ledger line (NFR-O2 of add-serve-sandbox-lifecycle):
 * one stop or dispose the sandbox-lifecycle sweep performed, with the object, its role and
 * ownership mode, the task it belonged to, the verdict category, a short reason, and its age.
 *
 * @param version the contract version; always {@code 1}
 * @param type the line-type discriminator; always {@code "sweepAction"}
 * @param instance the writing process's identity
 * @param at ISO-8601 UTC instant the action was performed
 * @param objectName the acted-on Docker object's own name
 * @param role the object's lifecycle role (e.g. {@code "main-box"})
 * @param mode the object's ownership mode ({@code "tracked"} | {@code "manual"})
 * @param taskKey the base task key the object belongs to
 * @param category the verdict category that licensed the action
 * @param reason a short explanation of the verdict
 * @param ageSeconds the object's age when measured; null when the verdict measured none
 */
public record SweepActionLineDto(
        int version,
        String type,
        InstanceDto instance,
        String at,
        String objectName,
        String role,
        String mode,
        String taskKey,
        String category,
        String reason,
        @Nullable Long ageSeconds) {}
