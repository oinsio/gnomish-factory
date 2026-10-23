# Gaps: developing the factory

Entries of [`../gaps.md`](../gaps.md) about the factory's own making: its build, its
release, its plugin contract and its work on itself. The form and the vocabulary are in the
index.

### GP-SPECS-NOT-LINKED-TO-NEEDS — A requirement names the change that made it, never the need it serves
**Breaks:** `UC-LINK-EVERY-SPEC-TO-A-TEST-AND-A-NEED`
**Where today:** `build-system/quality-gates`: Spec traceability line (the `**Implements**:` line only), Scenario-to-test link; nothing resolves a requirement to a story or a quality requirement of this directory, so a mechanism without a need is found only by hand
**Kind:** `missing`
**Resolution:** `open` (step D of `plan.md`: the `**Serves**:` line, the gate that resolves it, and the migration that writes it onto the main specs)

### GP-FACTORY-ON-ITSELF-HOST-ONLY — The factory works on its own repository only in host mode
**Breaks:** `UC-LET-THE-FACTORY-WORK-ON-ITSELF`
**Where today:** the law under `.gnomish/` binds `host` and says why: no sandbox image is built for this repository, and its end-to-end suites need a Docker daemon a box does not offer, so the gnome runs on the maintainer's machine with its files and network, no guard and no resource limits; the whole `./gradlew check` runs as the implement stage's last check under a two-hour bound
**Kind:** `one-medium-only`
**Resolution:** `open`

### GP-ONLY-TRACKER-AND-CHECK-PORTS-PLUGGABLE — A sandbox backend or an executor cannot be a plugin
**Breaks:** `UC-COMPILE-AGAINST-THE-CONTRACT-ALONE`
**Where today:** `plugin/plugin-discovery`: A port is plugin-ready by a four-point criterion (met by the tracker and check ports only); `plugin/adapter-binding-registry`: First-party trust table ratifies discovery (a sandbox binding not in the core-owned table is refused at startup); the executor port has no SPI factory, no registry and no config discriminator
**Kind:** `missing`
**Resolution:** `decided` for the sandbox port (`plugin/github-plugin`: Loading provider jars has a stated trust posture: a self-declared capability passport from an untrusted jar would be a trust hole, so the port stays first-party); `open` for the executor port

### GP-CONTRACT-SUITE-NOT-PUBLISHED — The contract suite runs only inside the factory's source tree
**Breaks:** `UC-PROVE-CONFORMANCE-WITHOUT-THE-SOURCE-TREE`
**Where today:** `tracker/tracker-port`: Contract suite covers lease maintenance; the suite lives in the `test-fixtures` module, and the only published artifact is `gnomish-plugin-api` (`plugin/plugin-api-contract`: The published jar graph is the compatibility baseline), so an adapter built outside the repository has no suite to run
**Kind:** `missing`
**Resolution:** `open`
