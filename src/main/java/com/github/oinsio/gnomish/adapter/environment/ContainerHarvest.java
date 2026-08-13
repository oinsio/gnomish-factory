package com.github.oinsio.gnomish.adapter.environment;

/**
 * The factory-side fetch seam behind the container adapter's {@link
 * TaskExecutionEnvironment#harvest} (design D3, FR5): the environment adapter
 * knows <em>which</em> container holds the working copy, while the git adapter
 * owns <em>how</em> a branch is fetched into the factory clone (its subprocess
 * runner and per-clone serialization are deliberately package-private there).
 * This interface lets the git package serve the environment package without the
 * environment package ever depending on git internals — the dependency points
 * git → environment only.
 *
 * <p>Contract (FR5): the implementation fetches with a factory-fixed refspec —
 * never a name produced inside the box — fast-forward-only and {@code
 * --no-recurse-submodules}; rewritten history is refused. Harvest precedes any
 * push; the push itself stays outside this seam, factory-side.
 *
 * <p>Implements FR5 of add-sandbox-core.
 */
@FunctionalInterface
public interface ContainerHarvest {

    /**
     * Fetches {@code branch} from the running container's working copy into the
     * factory clone, fast-forward-only.
     *
     * @param containerName the factory-derived task container name; never a
     *     value read from the environment
     * @param branch the task branch to fetch, factory-fixed; never null
     */
    void fetch(String containerName, String branch);
}
