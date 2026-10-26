package com.github.oinsio.gnomish.sandbox.environment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One streaming {@code docker exec}: the argv, which names each variable it forwards into the box
 * as a value-less {@code -e NAME}, the environment that supplies those values to the docker
 * client, and the declared credential names the client must not inherit (design D2, D3 of
 * fix-docker-exec-env-argv). Only {@link ContainerExecLaunches#launch} builds one, deriving the
 * argv's names from the environment's own keys, so the two cannot diverge.
 *
 * <p>The compact constructor copies every component: the argv and the withheld names as
 * unmodifiable copies, the environment as an unmodifiable copy of an insertion-ordered map, so
 * its iteration order stays the order of the {@code -e} flags (the composed order the debug log
 * shows) — {@code Map.copyOf} would lose it.
 *
 * <p>Implements FR3, NFR-S4 of fix-docker-exec-env-argv.
 *
 * @param argv the {@code docker} arguments, without the binary; never null
 * @param environment the variables the docker client resolves the {@code -e NAME} flags from, in
 *     flag order; never null, empty for an exec that forwards nothing
 * @param withheld the declared credential names removed from the client's inherited environment;
 *     never null
 */
record DockerExecLaunch(List<String> argv, Map<String, String> environment, Set<String> withheld) {

    DockerExecLaunch {
        argv = List.copyOf(argv);
        environment = Collections.unmodifiableMap(new LinkedHashMap<>(environment));
        withheld = Set.copyOf(withheld);
    }
}
