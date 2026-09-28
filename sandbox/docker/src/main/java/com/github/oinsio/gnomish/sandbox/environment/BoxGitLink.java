package com.github.oinsio.gnomish.sandbox.environment;

import java.nio.file.Path;

/**
 * The git link between the factory and one box (design D11 of add-parameter-count-gate): the
 * factory's local clone the box's working copy is seeded from (design D3 of add-sandbox-core),
 * and the fetch that brings the box's commits back into that clone (FR5 of add-sandbox-core).
 * The two are one value because neither names a thing without the other — a seed source with no
 * way back is a leak, a harvest into nothing is a fetch with no destination.
 *
 * <p>A facade under ADR 0010, so it earns a method: {@link #harvest} is how the environment
 * fetches, rather than reading the port back out through an accessor. {@link #sourceClone()}
 * stays an accessor because the seed helper needs the path as a mount argument.
 *
 * <p>Implements FR6 of add-parameter-count-gate.
 *
 * @param sourceClone the factory clone working copies are seeded from; mounted read-only into the
 *     one-shot seed helper only, never the task container; never null
 * @param harvester the factory-side fetch behind {@code harvest()}; never null
 */
public record BoxGitLink(Path sourceClone, ContainerHarvest harvester) {

    /**
     * Fetches {@code branch} from the named container's working copy into the factory clone,
     * fast-forward-only, through the harvest port.
     *
     * @param container the factory-derived task container name; never a value read from the box
     * @param branch the task branch to fetch, factory-fixed; never null
     */
    public void harvest(String container, String branch) {
        harvester.fetch(container, branch);
    }
}
