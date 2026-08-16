/**
 * The dashboard page's data readers and HTML rendering (add-dashboard-page).
 * This task group holds the daemon-section reader: it turns the serve
 * daemon's {@code snapshot.json} (add-serve-observability) into a view
 * model the page can render without ever throwing on a missing or
 * unreadable file (FR3), and flags staleness/dead-daemon per the
 * operator-guide rule generalized in design D3 (FR4).
 *
 * <p>Implements FR3, FR4 of add-dashboard-page.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.dashboard;

import org.jspecify.annotations.NullMarked;
