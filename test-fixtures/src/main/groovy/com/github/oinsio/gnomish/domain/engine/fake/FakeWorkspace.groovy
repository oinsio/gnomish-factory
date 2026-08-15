package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.domain.engine.port.Workspace

/**
 * A trivial {@link Workspace} for engine tests: the engine never inspects a
 * workspace, so a marker instance is all any spec needs to thread through
 * executor and check-runner calls.
 *
 * <p>Test fake for the add-stage-engine ports; not production code, never
 * PIT-mutated.
 */
class FakeWorkspace implements Workspace {}
