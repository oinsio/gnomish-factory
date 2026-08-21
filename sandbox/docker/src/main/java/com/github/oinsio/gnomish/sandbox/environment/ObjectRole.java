package com.github.oinsio.gnomish.sandbox.environment;

/**
 * The lifecycle role of a listed Docker object (`sandbox-lifecycle` decision matrix, `
 * execution-environment` delta "Container realization of lifecycle roles"). {@link #MAIN_BOX} is
 * the only role whose durable-work protection differs (stop, never dispose, while running);
 * {@link #GUARD}, {@link #JUDGE}, {@link #VERIFICATION}, and {@link #SEED_HELPER} are
 * reconstructible by construction and are disposed at once when unowned; {@link #UNRECOGNIZED}
 * takes the fail-safe fallback row so a newer build's object shapes are never insta-disposed by
 * an older sweep.
 */
enum ObjectRole {
    MAIN_BOX,
    JUDGE,
    VERIFICATION,
    GUARD,
    SEED_HELPER,
    UNRECOGNIZED;

    /** Guard, judge, verification, and seed-helper objects hold no durable work (design D2). */
    boolean disposableOnSight() {
        return this == GUARD || this == JUDGE || this == VERIFICATION || this == SEED_HELPER;
    }
}
