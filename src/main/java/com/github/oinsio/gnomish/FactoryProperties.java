package com.github.oinsio.gnomish;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Immutable typed configuration of a factory instance, bound from the {@code factory.*}
 * external properties via constructor binding (design D4). Validation is plain Java
 * triggered by the compact constructor — directly spec-able and mutation-testable,
 * no Bean Validation.
 *
 * <p>Implements FR3 of add-project-skeleton.
 *
 * @param instanceId unique identifier of this factory instance
 *     ({@code factory.instance-id}); never null or blank
 */
@ConfigurationProperties("factory")
public record FactoryProperties(String instanceId) {

    public FactoryProperties {
        instanceId = requireValidInstanceId(instanceId);
    }

    /**
     * Fails fast on a missing or blank instance id. Kept as an explicit method rather
     * than inline in the compact constructor: PIT's record filter suppresses all
     * mutations inside a record's canonical constructor, which would silently exempt
     * the validation logic from the 100% mutation gate (FR6). The parameter is
     * {@code @Nullable} because framework property binding constructs the record
     * reflectively and can pass null despite this package's {@code @NullMarked} default.
     */
    private static String requireValidInstanceId(@Nullable String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("factory.instance-id must not be null or blank");
        }
        return instanceId;
    }
}
