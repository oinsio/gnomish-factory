package com.github.oinsio.gnomish.sandbox.environment;

import java.util.Map;

/** One object from a factory-scoped {@code ps -a}/{@code volume ls}/{@code network ls} listing. */
record ListedDockerObject(String name, ObjectKind kind, Map<String, String> labels) {}
