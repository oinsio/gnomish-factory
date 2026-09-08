package com.github.oinsio.gnomish.baseref;

/**
 * What an allowed base's refs are for. The vocabulary classifies roles precisely so a later change can gate
 * on them (task class × branch role eligibility); this version carries the role and decides nothing
 * with it.
 *
 * <p>Exactly two roles exist, and deliberately so: environment-style deploy pointers are not allowed-base
 * material, and a third role would be a new vocabulary rather than a new value.
 *
 * <p>Implements FR1 of add-base-ref-resolution.
 */
public enum BranchRole {

    /** Where ordinary work lands — a trunk, a {@code develop} line, a long-lived feature base. */
    DEVELOPMENT,

    /** A maintained release line, the base of hotfixes and backports. */
    RELEASE;

    /**
     * The role a allowed base takes when it declares none. Development, because the zero-configuration
     * case is a project branching everything from its trunk.
     *
     * @return {@link #DEVELOPMENT}
     */
    public static BranchRole defaultRole() {
        return DEVELOPMENT;
    }
}
