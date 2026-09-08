/**
 * Pipeline-law freezing (D14, FR19 of add-sandbox-core): the stage instructions and
 * judge acceptance-criteria content of one invocation, read once at invocation start
 * from the gnome-unwritable law source and held immutable for the invocation. The
 * executor/judge/briefing adapters read control files and criteria from here, never
 * lazily from the working copy, so a running task cannot rewrite its own instructions
 * or weaken its own acceptance criteria.
 *
 * <p>The law's source is the {@code LawSource} seam (FR11 of add-base-ref-resolution),
 * with two realizations: the factory clone's working tree, and git objects at the law
 * commit for every path that resolved a ref.
 *
 * <p>Depends only on {@code adapter.pipeline} (the path-traversal guard), {@code
 * gitobjects} (reading law by ref) and the domain pipeline model; the agent and console
 * adapters depend on this package for their frozen reads.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.law;

import org.jspecify.annotations.NullMarked;
