## ADDED Requirements

### Requirement: One home directory holds all operator state
All operator state SHALL live under the factory home — the directory named by `GNOMISH_HOME`, default `~/.gnomish` — in one layout: `factory.yaml` (host configuration) and `secrets/` (host secrets) at the root; `logs/` for commands that run with no project; and one folder per registered project, `projects/<name>/`, holding `project.yaml`, `secrets/`, `logs/`, `serve/<instance>/` and `worktrees/<clone>/<task>/`. No factory component SHALL derive an operator path from the user's home directory by itself. Transient egress-guard configuration stays in the system temporary directory.
<!-- implements FR1, NFR-P1 of add-project-registry -->

#### Scenario: Relocating the home moves everything
- **WHEN** the factory runs with `GNOMISH_HOME=/srv/gnomish` for a registered project `widgets`
- **THEN** its configuration, secrets, log file, serve state and worktrees are all read from and written under `/srv/gnomish/projects/widgets/` or `/srv/gnomish/`, and nothing is written under `~/.gnomish`

### Requirement: Projects are registered explicitly by clone path
`gnomish project add <name> --dir=<path>` SHALL register the absolute, normalized path as a clone of project `<name>`, creating `projects/<name>/project.yaml` on first use and adding the clone to an existing project otherwise. Each clone SHALL carry a clone name, by default the last segment of its path, unique within the project. A project name SHALL match `[a-z0-9][a-z0-9._-]*`. A path already registered to any project, a path that is not a git working tree, or an invalid name SHALL be refused with the reason. The registration write SHALL be atomic: a failed `project add` leaves the registry as it was.
<!-- implements FR2, NFR-R1 of add-project-registry -->

#### Scenario: First clone creates the project
- **WHEN** the operator runs `gnomish project add widgets --dir=.` inside `~/src/widgets`
- **THEN** `projects/widgets/project.yaml` exists and lists the clone `widgets` at `/home/op/src/widgets`

#### Scenario: Second clone of the same project
- **WHEN** the operator then runs `gnomish project add widgets --dir=~/src/widgets-demo`
- **THEN** the project lists two clones, `widgets` and `widgets-demo`, sharing one `project.yaml`

#### Scenario: One path, one project
- **WHEN** the operator registers `~/src/widgets` under a second project name
- **THEN** the command is refused naming the project that already owns the path

### Requirement: Every project-scoped command resolves one registered clone
Every subcommand that accepts `--dir`, except `project add`, SHALL resolve the directory to exactly one registered clone by exact path, once per process, before any configuration beyond the host file is read and before any side effect; the command SHALL work in the clone that resolution matched and SHALL NOT derive its directory a second time. An unregistered directory SHALL be refused with a ready-to-paste `gnomish project add <suggested-name> --dir=<path>` line; a directory inside a registered clone SHALL be refused naming that clone's path. Either refusal exits with the usage-error exit code (2).
<!-- implements FR3, UX2 of add-project-registry -->

#### Scenario: Unregistered clone is refused with the fix
- **WHEN** `gnomish take --dir=~/src/newproj` runs and no project lists that path
- **THEN** the command exits with a usage error containing `gnomish project add newproj --dir=/home/op/src/newproj`
- **AND** no tracker call is made

#### Scenario: Subdirectory of a clone
- **WHEN** `gnomish run --dir=~/src/widgets/app` runs and `~/src/widgets` is registered
- **THEN** the command is refused naming `/home/op/src/widgets` as the registered clone to use

#### Scenario: A symlinked path reaches its registered clone
- **WHEN** `/home/op/w` is a symlink to the registered clone `/home/op/src/widgets` and `gnomish status --dir=/home/op/w` runs
- **THEN** the command resolves project `widgets` and works in `/home/op/src/widgets`

### Requirement: The registry is inspectable
`gnomish project list` SHALL print every project with its clones. `gnomish project show [<name>]` — the project resolved from `--dir` when no name is given — SHALL print the project's folder, each clone with its worktree folder, the log file and serve folder of the configured instance, and every effective `factory.*` value with its origin: file and line, command line, or built-in default.
<!-- implements FR4, NFR-O1 of add-project-registry -->

#### Scenario: Where a value came from
- **WHEN** `factory.git-network-timeout` is `5m` in `factory.yaml` and `10m` in `projects/widgets/project.yaml`
- **THEN** `gnomish project show widgets` prints `10m` with `projects/widgets/project.yaml:<line>` as its origin and notes the host value it overrides
