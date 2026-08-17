package com.github.oinsio.gnomish.app.port.secrets.fake

import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider

/**
 * A {@link SecretsProvider} backed by a plain map, honouring the port's fail-closed contract: an
 * absent or blank value resolves to {@link Optional#empty()}, never an empty string (NFR-S1 of
 * add-sandbox-core).
 *
 * <p>Exists because {@code TrackerAdapterFactory.create} now takes the provider as a method argument
 * rather than capturing it at construction (FR2, design D2 of add-plugin-architecture), so nearly
 * every {@code take}/{@code serve}/{@code board} spec has to hand one in. {@link #NONE} is the
 * common case: a spec whose adapter reads no credential at all.
 */
class MapSecretsProvider implements SecretsProvider {

    /** Resolves nothing — for specs whose tracker adapter reads no credential. */
    static final SecretsProvider NONE = new MapSecretsProvider([:])

    private final Map<String, String> values

    MapSecretsProvider(Map<String, String> values) {
        this.values = values
    }

    @Override
    Optional<String> find(String name) {
        String value = values[name]
        value == null || value.isBlank() ? Optional.empty() : Optional.of(value)
    }
}
