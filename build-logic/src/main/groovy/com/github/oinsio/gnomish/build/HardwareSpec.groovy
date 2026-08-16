package com.github.oinsio.gnomish.build

import com.sun.management.OperatingSystemMXBean
import groovy.transform.Immutable
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

import java.lang.management.ManagementFactory

/**
 * The host's memory and CPU size, read once per build (D3 of adapt-build-load-to-hardware).
 * Feeds the heavy-JVM budget formula in {@code test-conventions}, which decides how many 3 GB test
 * JVMs and mutation minion pools may coexist on this machine.
 *
 * <p>A {@code ValueSource} rather than a plain read in the script body because
 * {@code org.gradle.configuration-cache} is on (NFR-R2): Gradle records the obtained value with the
 * cached configuration and re-obtains it on every build to check it, so moving the repository to a
 * bigger or smaller machine invalidates the cache instead of silently reusing a budget sized for
 * different hardware.
 *
 * <p>{@code com.sun.management.OperatingSystemMXBean} is non-standard but present on every JDK this
 * project supports (Temurin/HotSpot per ADR 0001), and it reports what the platform interface does
 * not: total physical RAM. Parsing {@code /proc/meminfo} or {@code sysctl hw.memsize} instead would
 * mean platform-specific subprocesses for a value the JVM already exposes.
 *
 * <p>Caveat (proposal Q1): inside a memory-limited container the bean may report the host's RAM
 * rather than the cgroup limit. The {@code gnomish.heavyJvmSlots} override property is the answer
 * for such runners.
 */
abstract class HardwareSpec implements ValueSource<Machine, ValueSourceParameters.None> {

    /**
     * What the build needs to know about the machine. Value-typed with generated
     * {@code equals}/{@code hashCode}: the configuration cache compares a freshly obtained instance
     * against the recorded one to decide whether the cached configuration still fits this host.
     */
    @Immutable
    static class Machine implements Serializable {
        long totalRamBytes
        int processors
    }

    @Override
    Machine obtain() {
        def os = (OperatingSystemMXBean) ManagementFactory.operatingSystemMXBean
        new Machine(os.totalMemorySize, Runtime.runtime.availableProcessors())
    }
}
