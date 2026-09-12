## ADDED Requirements

### Requirement: Image-declared volumes are never anonymous
Before creating any factory container from an image — the egress guard, the main box, a judge box, a verification box — the container adapter SHALL read the image's declared volume paths from the runtime and SHALL mount a size-bounded ephemeral (`tmpfs`) filesystem over every declared path that is not already the destination of an explicit factory mount for that container. The factory SHALL therefore never cause an anonymous (unlabelled, runtime-named) volume to exist on the daemon. Declared paths the factory already mounts explicitly — the working copy volume in the box, the guard's read-only config directory — keep their explicit mount. The one-shot seed-clone helper is exempt: it runs as a self-removing container, and the runtime removes its anonymous volumes with it. No production code SHALL name a declared path of any particular image; the set is obtained from the image at creation time.
<!-- implements FR1, FR2, FR3, FR4, G1, G2 of fix-image-declared-volumes -->

#### Scenario: Guard image declaring a volume leaves no anonymous volume
- **WHEN** an environment is materialized with a guard image whose Dockerfile declares a `VOLUME` (the default `mitmproxy` image does)
- **THEN** the guard container runs with an ephemeral filesystem at that path, and the daemon's set of anonymous volumes is the same after the guard has been created and disposed as it was before

#### Scenario: Box image declaring a volume leaves no anonymous volume
- **WHEN** an environment is materialized from a task image declaring a `VOLUME` at some path outside the working copy, a round writes into that path, and the environment is disposed
- **THEN** the write succeeded inside the box, and the daemon's set of anonymous volumes is unchanged by the whole sequence

#### Scenario: Explicit factory mounts are not overridden
- **WHEN** an image declares a `VOLUME` at the exact path the factory mounts explicitly (the working copy in the box, the config directory in the guard)
- **THEN** the explicit factory mount stands and no ephemeral filesystem is layered over it

#### Scenario: Judge and verification boxes are covered by the same rule
- **WHEN** a fresh judge or verification environment (`<key>-j`, `<key>-v`) is materialized from an image declaring a `VOLUME`
- **THEN** its container carries the same ephemeral overrides as the main box would, and no anonymous volume is created

#### Scenario: Declared-volume read failure is an infrastructure failure
- **WHEN** the runtime cannot report the image's declared volumes (the daemon is unreachable, or the answer is unparseable)
- **THEN** the materialize fails as an infrastructure failure per the existing runtime-outage requirement — it never proceeds with an empty override set
<!-- implements NFR-R1 of fix-image-declared-volumes -->

### Requirement: Overridden declared paths are named in the creation anchor
The environment-creation lifecycle anchor (the existing INFO line stating that a container environment was created) SHALL name the declared paths that were made ephemeral for the box, stating explicitly when there were none; the guard's creation SHALL record its overridden paths at DEBUG. No new operator-plane (WARN/ERROR) event is introduced.
<!-- implements NFR-O1, UX1 of fix-image-declared-volumes -->

#### Scenario: Operator sees which paths were made ephemeral
- **WHEN** an environment is created from an image declaring `/cache` and `/var/lib/tool`
- **THEN** the creation anchor line names both paths as ephemeral overrides

#### Scenario: Image with no declared volumes
- **WHEN** an environment is created from an image declaring no volumes
- **THEN** the creation anchor line states that no declared path was overridden
