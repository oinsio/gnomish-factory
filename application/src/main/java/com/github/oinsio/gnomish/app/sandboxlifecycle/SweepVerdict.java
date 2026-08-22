package com.github.oinsio.gnomish.app.sandboxlifecycle;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * One verdict event, emitted per evaluated object (`sandbox-lifecycle` "Uniform verdict events",
 * FR9): category, the object's own name, its lifecycle role and ownership mode as plain labels
 * (so this record needs no dependency on the runtime-adapter enums that classify them —
 * {@code sandbox/docker} is the only module that knows what a "judge box" IS; here it is just
 * text), the task key it belongs to, a short human reason, and its age when one was measured. All
 * entry points (`run`, `take`, `serve`) emit the identical shape through this one listener seam;
 * they differ only in where events sink (design D6).
 *
 * @param category the verdict category; never null
 * @param objectName the object's own Docker name; never blank
 * @param role the object's lifecycle role, as a short label (e.g. {@code "main-box"}); never blank
 * @param mode the object's ownership mode label ({@code "tracked"} | {@code "manual"}); never blank
 * @param taskKey the base task key this object belongs to; never blank
 * @param reason a short, human-readable explanation of the verdict; never blank
 * @param age the object's age at evaluation time — since creation for a stopped or container-less
 *     object, since started-at for a running one, which is the quantity a running-box decision is
 *     actually made from. Every verdict the sweep emits carries one (`sandbox-lifecycle`,
 *     "Uniform verdict events"); the field stays nullable so a sink tolerates a future producer
 *     that reaches a verdict without measuring an age at all, and every sink must handle that.
 */
public record SweepVerdict(
        SweepVerdictCategory category,
        String objectName,
        String role,
        String mode,
        String taskKey,
        String reason,
        @Nullable Duration age) {}
