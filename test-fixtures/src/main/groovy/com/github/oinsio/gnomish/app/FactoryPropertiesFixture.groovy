package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties

/**
 * Shared {@link FactoryProperties} builder for app-layer specs, defaulting to
 * the dominant {@code new FactoryProperties('test-instance', null, null,
 * null, null)} literal seen across both {@code :application}'s and {@code
 * :bootstrap}'s spec trees. Pass overrides by key — {@code instanceName},
 * {@code agentCliBinary}, {@code agentCliEnvPassthrough}, {@code tracker},
 * {@code check} — for the sites that vary one of these; {@code tracker}
 * defaults to {@code null} (= default {@code Tracker}).
 *
 * <p>A plain Groovy trait, composable alongside the composition-root
 * fixtures ({@code AppAssemblyFixture}) and the port-fake fixtures ({@code
 * RunChainFakes}) that each used to carry their own copy of this method.
 */
trait FactoryPropertiesFixture {

    FactoryProperties testProperties(Map overrides = [:]) {
        new FactoryProperties(
                overrides.getOrDefault('instanceName', 'test-instance') as String,
                overrides['agentCliBinary'] as String,
                overrides['agentCliEnvPassthrough'] as List<String>,
                overrides['tracker'] as FactoryProperties.Tracker,
                overrides['check'] as Map<String, Map<String, Object>>)
    }
}
