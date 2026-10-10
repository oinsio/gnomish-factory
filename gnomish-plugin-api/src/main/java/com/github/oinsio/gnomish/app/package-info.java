/**
 * The plugin SPI of the published surface: the factories a tracker or check provider implements
 * ({@link com.github.oinsio.gnomish.app.TrackerAdapterFactory}, {@link
 * com.github.oinsio.gnomish.app.CheckClientFactory}), the host-built contexts they are created
 * from, and the validators of their configuration subsections.
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
