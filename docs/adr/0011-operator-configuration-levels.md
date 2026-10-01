# ADR 0011: Operator Configuration Levels

Status: accepted (2026-09-30, introduced by `add-project-registry`; its design
D3–D6 took these decisions, recorded here because a change's `design.md`
archives with the change and governs nothing afterwards)

## Context

One factory installation serves several projects from one host. Before this
decision, `factory.*` configuration had a single level: Spring's usual
sources — the bundled `application.yaml`, the command line, environment
variables through relaxed binding, system properties — with unknown keys
silently ignored. Half of the keys describe a project rather than the
installation: the sandbox image and the egress allowlist follow one project's
toolchain and hosts, and `factory.bindings.stages.<stage>` names stages that
exist only in one project's pipeline. With two projects on one host, one
value cannot serve both.

The operator stand showed the consequence: one launcher script per project
injecting the sandbox image, the egress allowlist, the environment
passthrough and the instance name as command-line options. It also showed the
security gap. Any source, a stray `FACTORY_*` environment variable included,
could widen the sandbox, and nothing said which settings may do so, or from
where.

## Decision

### Four sources, in rising precedence

The `factory.*` configuration is assembled from:

1. **Built-in defaults** — on the `@ConfigurationProperties` records
   themselves. The bundled `application.yaml` carries no `factory.*` key and
   no `spring.config.import`; a resource scan in `:bootstrap` keeps it so.
2. **The host file** — `GNOMISH_HOME/factory.yaml`.
3. **The project file** — the `factory:` block of the resolved project's
   `GNOMISH_HOME/projects/<name>/project.yaml`. The same file lists the
   project's clones under `clones:`.
4. **The command line** — `--factory.<key>=<value>`, and JVM system
   properties.

Each file is optional. Map-valued keys merge by key across sources;
list-valued keys are replaced whole by the highest source that sets them. The
target repository's `.gnomish/` files are never a source of `factory.*` keys:
the repository under work cannot configure the factory that works on it.

Environment variables are not a source. A set `FACTORY_*` variable is a
violation whose fix names the equivalent file line or command-line option.

### Every key declares its level, beside its definition

Every `factory.*` key has exactly one level:

| Level              | Accepted from                                     |
|--------------------|---------------------------------------------------|
| `host`             | the host file, the command line                   |
| `project`          | the project file, the command line                |
| `any`              | the host file, the project file, the command line |
| `sandbox-boundary` | the project file only                             |

The level is declared by `@ConfigLevel` (the JDK-only leaf
`:operatorconfig`) on the component of the `@ConfigurationProperties`
record that defines the key. A plugin-contributed subtree
(`factory.check.<provider>`, `factory.connections.<name>`) takes the level of
its root. The key-to-level table is derived by reflection, with the relaxed
names Spring binds; it is never written by hand, because a written table
would be a second copy of the records to keep in step. A component without
the annotation fails the build (`ConfigLevelCoverageSpec`).

This ADR does not list the level of every key: the annotations are the only
list, and `gnomish project show` prints each key with its level. Changing a
key's level is a one-line annotation change.

### Sandbox-boundary keys

A sandbox-boundary key widens or selects the sandbox a gnome runs in:
`factory.bindings.*`, `factory.sandbox.image`,
`factory.sandbox.egress-allowlist` and `factory.sandbox.env-passthrough`. It
is read from the resolved project's own file and nowhere else. The command
line is refused too: the quick one-run toggle is exactly what lets a boundary
drift away from the file an operator reviews. Everything that shapes one
project's sandbox can therefore be read in one file.

A new key that selects an adapter binding, admits a network destination, or
passes a variable into a box is a sandbox-boundary key.

### One check, one complete report, before anything else happens

The operator configuration loader (`OperatorConfigLoader`, a Spring
`EnvironmentPostProcessor`) checks every source before any bean exists, and
therefore before any tracker call, branch, worktree or box. It stops startup
when it finds:

- a key in a source its level does not admit;
- a `factory.*` key that no record defines, removed keys included;
- a `FACTORY_*` environment variable;
- a configuration file writable by group or others (`chmod go-w` fixes it);
- a `--dir` that is not a registered clone, or lies inside one.

Every violation is collected, never only the first. Each line reads
`<what> — found in <where> — <why> — <fix>`: the location is the file and
line, the command-line option or the variable name, and the fix names the
file to move the key to or the command to run. The report is printed once,
without a stack trace, and the process exits with the usage-error code 2. No
new exit code exists for configuration.

### Origins are kept

Both files are read with Spring's YAML loader, which keeps the file and line
of every value. The violation report cites them, and `gnomish project show`
prints every effective value with its origin: file and line, command line,
or built-in default.

## Consequences

Positive:

- One host serves several projects with no launcher script. A per-project
  setting lives in that project's file.
- A misplaced, misspelled, removed or unsafe setting is reported at startup
  with its fix, all at once, and not discovered later as a setting that
  silently had no effect.
- A new key cannot ship without a declared level, because the build refuses
  it.

Negative:

- The operator loses the one-run `--factory.bindings.default=host` toggle
  and must edit the project file. This is intentional.
- Environment-variable configuration, common in container deployments, is
  unavailable. A deployment mounts `GNOMISH_HOME` or passes command-line
  options instead.
- Every project-scoped command reads the host file and every registered
  project file once per process, which is a handful of small files.

## Alternatives Considered

**`spring.config.import` of the two files.** Spring would merge them, but it
has no per-source check, no project resolution, and unknown keys stay
silent.

**Validation in each `@ConfigurationProperties` constructor.** A constructor
sees the merged value, not the source it came from, so it cannot tell a
boundary key set in the project file from one set on the command line.

**A hand-written key table or `additional-spring-configuration-metadata.json`.**
Either one is a second list of keys to keep in step with the records by hand.

**The level annotation in `:domain` or `:sandbox:core`.** The engine layer has
no business knowing operator files. `:application` and `:sandbox:core` both
need the annotation, and only one of them can reach the other, so the
annotation lives in a leaf that both reach.

**Environment variables as a fourth file-like source.** A variable is
invisible in any file an operator reviews and is inherited by every child
process. For sandbox-boundary keys, that is the gap this decision closes.

## See also

- `docs/glossary.md`, "Operator configuration": factory home, registered
  project, configuration level, sandbox-boundary key.
- ADR 0004 (logging policy): the log file location this loader publishes.
