/**
 * The plugin SPI of the published surface: the factory a tracker provider implements ({@link
 * com.github.oinsio.gnomish.app.TrackerAdapterFactory}), the host-built context it is created from,
 * and the validator of its configuration subsection. A verdict check is not a plugin: it is an
 * executor program authored against the executor contract (task 6.1 of
 * migrate-verify-to-executor-contract).
 *
 * <p>The package is split with {@code :application}, which carries its own {@code package-info};
 * this one is what the published jar ships, so a plugin's own nullness checker reads these types
 * as null-marked.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable
 * ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.app;

import org.jspecify.annotations.NullMarked;
