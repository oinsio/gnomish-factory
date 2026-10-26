package com.github.oinsio.gnomish.app;

/**
 * Where the secrets port finds a named secret, never what it holds (NFR-S1 of add-doctor-command):
 * the question {@code doctor}'s secrets check asks of the one adapter that knows its own places, so
 * the check names the place a secret was found or the places searched without holding a folder
 * name, a variable name or a value itself (FR4). The composition root binds the secrets adapter,
 * which answers it by the same resolution its {@code find} runs.
 *
 * <p>A role interface the application owns: specs double it with a type of their own.
 *
 * <p>Implements FR1, FR4, NFR-S1 of add-doctor-command.
 */
@FunctionalInterface
public interface SecretLocator {

    /**
     * Locates {@code name} by the resolution the secrets port runs for it.
     *
     * @param name the secret's name; never null
     * @return where it was found, or every place searched; never null, never a value
     * @throws UsageException when the port would refuse the name or a place holding it
     */
    SecretLocation locate(String name);
}
