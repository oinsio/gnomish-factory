package com.github.oinsio.gnomish.operatorconfig;

/**
 * Fixture for {@code ConfigLevelSpec}: one record component carrying {@link ConfigLevel}.
 *
 * <p>Declared in Java rather than inside the spec because IntelliJ's Groovy support treats a Groovy
 * record header as a parameter list and flags a {@code RECORD_COMPONENT}-only annotation there as
 * not applicable; the Groovy compiler places it correctly, but the false error would sit on the
 * spec permanently.
 *
 * <p>FR6 of add-project-registry.
 */
record LevelledRecord(@ConfigLevel(Level.SANDBOX_BOUNDARY) String image) {}
