package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The product version of the running factory — the one reader of it at runtime.
 *
 * <p>The release build stamps the version into the boot jar as {@code build.version} of Boot's
 * {@code META-INF/build-info.properties} ({@code product-version-conventions} sets it, {@code
 * :bootstrap}'s {@code packaging.gradle} writes it); {@link #current()} reads that resource once
 * per process. Every place that reports the factory's version — the serve snapshot and ledger,
 * {@code gnomish --version} — takes it from here, never from a manifest attribute: {@code
 * Package.getImplementationVersion()} answers for whichever nested jar holds the calling class,
 * which is how the wrong version was reported before ({@code FactoryVersionBoundarySpec} keeps that
 * call out of production code).
 *
 * <p>{@link #DEVELOPMENT} is the answer when the resource is absent (specs running from class
 * folders). A resource that is present but unreadable, malformed or without a version yields the
 * same answer with a {@code GF150} warning: the version is reported, never decided on, so a corrupt
 * resource does not stop the factory, and the release workflow — which compares the packaged
 * {@code --version} with the tag — is where such a jar is stopped (FR8).
 *
 * @param value the version string, {@code 0.0.0-dev} outside a release build; never blank
 *     <p>Implements FR3, FR6 of add-release-pipeline (design D2).
 */
public record FactoryVersion(String value) {

    /** The version of every build that was not handed a release version. */
    public static final FactoryVersion DEVELOPMENT = new FactoryVersion("0.0.0-dev");

    static final String BUILD_INFO = "META-INF/build-info.properties";

    private static final String VERSION_KEY = "build.version";

    private static final Logger log = LoggerFactory.getLogger(FactoryVersion.class);

    public FactoryVersion {
        if (value.isBlank()) {
            throw new IllegalArgumentException("factory version must not be blank");
        }
    }

    /** The running factory's version, read from the class path on first use and kept. */
    public static FactoryVersion current() {
        return Current.VERSION;
    }

    /** Reads the version from the build info {@code loader} sees, or {@link #DEVELOPMENT}. */
    static FactoryVersion readFrom(ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream(BUILD_INFO)) {
            return in == null ? DEVELOPMENT : parse(in);
        } catch (IOException | IllegalArgumentException e) {
            log.warn(
                    OperatorEvent.FACTORY_VERSION_UNREADABLE.head()
                            + "build info {} could not be read; reporting the factory version as {}",
                    BUILD_INFO,
                    DEVELOPMENT,
                    e);
            return DEVELOPMENT;
        }
    }

    /** Throws {@link IllegalArgumentException} for a malformed escape, a missing or blank version. */
    private static FactoryVersion parse(InputStream in) throws IOException {
        Properties properties = new Properties();
        properties.load(in);
        @Nullable String version = properties.getProperty(VERSION_KEY);
        if (version == null) {
            throw new IllegalArgumentException(BUILD_INFO + " carries no " + VERSION_KEY);
        }
        return new FactoryVersion(version.strip());
    }

    @Override
    public String toString() {
        return value;
    }

    /** Initialization-on-demand holder: the resource is read once, when first asked for. */
    private static final class Current {
        static final FactoryVersion VERSION = readFrom(FactoryVersion.class.getClassLoader());
    }
}
