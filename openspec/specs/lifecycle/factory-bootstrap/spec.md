# factory-bootstrap

## Purpose

Defines how a factory instance boots as a headless, outbound-only worker: it starts a minimal Spring context, binds its configuration into an immutable typed object that fails fast on invalid input, logs through SLF4J/Logback, and shuts down cleanly.

## Requirements

### Requirement: Application startup and clean shutdown
The factory application SHALL run as a headless worker: boot a plain Spring context from `spring-boot-starter` alone, initiate every network exchange itself (outbound-only), and exit with code 0 when no work is requested.

#### Scenario: Clean boot
- **WHEN** the application is started with a valid configuration
- **THEN** the Spring context initializes without errors
- **AND** the process terminates with exit code 0

#### Scenario: Headless outbound-only runtime
- **WHEN** the application is running
- **THEN** the booted context is a plain annotation-config context
- **AND** the classpath is the headless `spring-boot-starter` set, so the application itself is the only party that can initiate a network exchange

### Requirement: Typed configuration binding
The application SHALL bind configuration from `application.yaml` into an immutable typed properties object and SHALL fail startup on invalid configuration with an error message naming the offending property.

#### Scenario: Valid configuration binds
- **WHEN** `application.yaml` contains valid factory properties
- **THEN** the typed properties object exposes those values
- **AND** the object is immutable (constructor binding, no setters)

#### Scenario: Invalid configuration fails fast
- **WHEN** a required property is missing or has an invalid value
- **THEN** startup fails before any work begins
- **AND** the error message names the property that failed validation

#### Scenario: Environment override
- **WHEN** a property is set both in `application.yaml` and as an environment variable
- **THEN** the environment variable value wins

### Requirement: Logging through SLF4J and Logback
All application logging SHALL go through the SLF4J API with Logback as the backend; the log level SHALL be changeable without recompilation.

#### Scenario: Log level from configuration
- **WHEN** the configured log level is set to DEBUG via configuration or environment
- **THEN** DEBUG messages appear in the output without rebuilding the application
