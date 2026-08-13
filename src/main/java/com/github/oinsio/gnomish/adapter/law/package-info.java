/**
 * Pipeline-law freezing (D14, FR19 of add-sandbox-core): the stage instructions and
 * judge acceptance-criteria content of one invocation, read once at invocation start
 * from the gnome-unwritable law source and held immutable for the invocation. The
 * executor/judge/briefing adapters read control files and criteria from here, never
 * lazily from the working copy, so a running task cannot rewrite its own instructions
 * or weaken its own acceptance criteria.
 *
 * <p>Depends only on {@code adapter.pipeline} (the path-traversal guard) and the domain
 * pipeline model; the agent and console adapters depend on this package for their
 * frozen reads.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.law;

import org.jspecify.annotations.NullMarked;
