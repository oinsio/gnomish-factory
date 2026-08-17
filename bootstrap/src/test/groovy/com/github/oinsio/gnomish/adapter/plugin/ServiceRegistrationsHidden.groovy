package com.github.oinsio.gnomish.adapter.plugin

/**
 * A view of the running classpath with every {@code META-INF/services} registration for one SPI
 * taken away — the classpath of a distribution in which nobody ever installed a provider for that
 * port (M1 of add-plugin-architecture).
 *
 * <p>Peer of {@link GithubArtifact#hiddenFrom}, and deliberately not the same thing: that one hides
 * the registrations <em>one artifact</em> contributes, to stage "the github jar was removed"; this
 * one hides <em>every</em> registration of one SPI, to ask the sharper question — with nothing
 * declared anywhere, does a registry still come back populated? A hardwired {@code Map.of(...)}
 * fallback of any shape would answer yes, whatever it was named and however it was spelled.
 *
 * <p>Discovery reads registrations and nothing else, so this is exactly as strong as deleting every
 * provider jar, and it needs no second JVM.
 */
class ServiceRegistrationsHidden {

    /** Where {@code ServiceLoader} looks: one resource per SPI, contributed by any number of jars. */
    static final String SERVICES_DIR = 'META-INF/services/'

    /**
     * The running classpath minus every registration of {@code spi}.
     *
     * @param spi the service interface whose registrations disappear; never null
     * @param parent the loader to delegate everything else to; never null
     */
    static ClassLoader of(Class<?> spi, ClassLoader parent) {
        new SpilessClassLoader(SERVICES_DIR + spi.name, parent)
    }
}

/** Delegates everything to its parent except the one service-registration resource it blinds. */
class SpilessClassLoader extends ClassLoader {

    private final String hidden

    SpilessClassLoader(String hidden, ClassLoader parent) {
        super(parent)
        this.hidden = hidden
    }

    @Override
    Enumeration<URL> getResources(String name) {
        name == hidden ? Collections.enumeration([]) : super.getResources(name)
    }
}
