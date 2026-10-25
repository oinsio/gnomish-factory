package com.github.oinsio.gnomish.sandbox.environment;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import org.jspecify.annotations.Nullable;

/**
 * The image contract the factory was built with — the one reader of the values the sandbox base
 * image and the factory share (FR6, design D2 of add-sandbox-base-image).
 *
 * <p>The values have one source, {@code sandbox-image/image-contract.properties} at the repository
 * root: the image build reads it into build arguments, and this module's build copies it unchanged
 * into its resources as {@link #RESOURCE}, which {@link #current()} reads once per process. No Java
 * source spells the contract version, the guard alias or the guard port; every consumer takes them
 * from here.
 *
 * <p>The contract is decided on, never only reported, so a missing resource, a missing key or a
 * malformed value is a build defect and fails loudly with the resource and the key named — there is
 * no default.
 *
 * @param contractVersion the version the sandbox image's {@code io.gnomish.image-contract} label must
 *     carry; at least 1
 * @param guardAlias the network alias the egress guard answers on inside a box; never blank, no
 *     whitespace
 * @param guardPort the port the egress guard's proxy listens on; within 1..65535
 *     <p>Implements FR6 of add-sandbox-base-image.
 */
public record ImageContract(int contractVersion, String guardAlias, int guardPort) {

    /** The class-path resource this module's build copies from the contract's source file. */
    public static final String RESOURCE = "com/github/oinsio/gnomish/sandbox/environment/image-contract.properties";

    /**
     * The label a sandbox image carries its contract version in — the contract's own name for it,
     * which the base image's build stamps from {@link #contractVersion} and {@link
     * ImageContractCheck} reads.
     */
    public static final String LABEL = "io.gnomish.image-contract";

    static final String VERSION_KEY = "contract-version";
    static final String ALIAS_KEY = "guard-alias";
    static final String PORT_KEY = "guard-port";

    private static final int MAX_PORT = 65_535;

    public ImageContract {
        if (contractVersion < 1) {
            throw new IllegalArgumentException(VERSION_KEY + " must be at least 1, was " + contractVersion);
        }
        if (guardAlias.isEmpty() || guardAlias.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                    ALIAS_KEY + " must be a non-blank name without whitespace, was '" + guardAlias + "'");
        }
        if (guardPort < 1 || guardPort > MAX_PORT) {
            throw new IllegalArgumentException(PORT_KEY + " must be within 1.." + MAX_PORT + ", was " + guardPort);
        }
    }

    /** The contract of the running factory, read from the class path on first use and kept. */
    public static ImageContract current() {
        return Current.CONTRACT;
    }

    /**
     * Reads the contract from the {@link #RESOURCE} {@code loader} sees.
     *
     * @throws IllegalStateException when the resource is absent, unreadable, or carries a missing
     *     or malformed value; the message names the resource and the key
     */
    static ImageContract readFrom(ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "image contract resource " + RESOURCE
                                + " is not on the class path; the :sandbox:docker build copies it from sandbox-image/image-contract.properties");
            }
            return parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException("image contract resource " + RESOURCE + " could not be read", e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "image contract resource " + RESOURCE + " is malformed: " + e.getMessage(), e);
        }
    }

    private static ImageContract parse(InputStream in) throws IOException {
        Properties properties = new Properties();
        properties.load(in);
        return new ImageContract(
                number(properties, VERSION_KEY), text(properties, ALIAS_KEY), number(properties, PORT_KEY));
    }

    private static String text(Properties properties, String key) {
        @Nullable String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("it carries no " + key);
        }
        return value.strip();
    }

    private static int number(Properties properties, String key) {
        String value = text(properties, key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a whole number, was '" + value + "'", e);
        }
    }

    /** Initialization-on-demand holder: the resource is read once, when first asked for. */
    private static final class Current {
        static final ImageContract CONTRACT = readFrom(ImageContract.class.getClassLoader());
    }
}
