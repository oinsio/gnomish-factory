package com.github.oinsio.gnomish.app.project;

import java.nio.file.Path;

/**
 * What the directory {@code doctor} works in resolved to, as the configuration loader found it
 * (FR1, FR2 of add-doctor-command): the one {@link ProjectRegistry#resolve} answer the loader read
 * for the process, kept for the registration check instead of being refused, so {@code doctor}
 * names the directory and the registry's own {@code project add} line without resolving {@code
 * --dir} a second time.
 *
 * <p>{@code named} says whether the operator asked for the directory with {@code --dir} or it is
 * the working directory: an unregistered directory the operator named is a failed check, the
 * working directory outside any clone only skips the project checks (FR2).
 *
 * <p>Implements FR1, FR2 of add-doctor-command.
 */
public sealed interface ProjectResolution {

    /**
     * The directory is a registered clone.
     *
     * @param registeredClone the clone the registry resolved
     */
    record Registered(RegisteredClone registeredClone) implements ProjectResolution {}

    /**
     * No project registers the directory.
     *
     * @param dir the absolute directory
     * @param named whether {@code --dir} named it
     * @param refusal the registry's own refusal, which names the {@code project add} line
     */
    record Unregistered(Path dir, boolean named, String refusal) implements ProjectResolution {}

    /**
     * The registry could not be read, so the directory could not be judged; the read failure is
     * one of the configuration's violations.
     *
     * @param dir the absolute directory
     * @param named whether {@code --dir} named it
     */
    record Unread(Path dir, boolean named) implements ProjectResolution {}
}
