# sandbox/execution-environment — delta for own-git-invocation-policy

## MODIFIED Requirements

Layered on `The box's git meets the floor before the seed clone` as it stands in the main spec
after `tighten-spec-contract` (archived 2026-10-14), where the box shared the factory's floor;
this text judges the box against its own floor, which keeps the value 2.51.0 while the factory's
rises to 2.56.0 (design D11 of own-git-invocation-policy). The scenarios are unchanged.

### Requirement: The box's git meets the floor before the seed clone
The container adapter SHALL, before every seed clone of a box (a fresh materialize, and a reattach that applies a commit pin), run the image's own `git --version` in a one-shot, network-less helper carrying the environment's ownership labels, and SHALL refuse the image when the reported version is below the box's git floor or no version is reported. A claimed task whose box image is refused SHALL be released, not aborted.
**Implements**: NFR-S2 of tighten-spec-contract; FR10 of own-git-invocation-policy
**Notes**: The seed clone reads the factory clone, on the reftable ref backend (readable since git 2.45), with the image's git; the box's floor is 2.51.0 and is held apart from the factory's 2.56.0 until `add-sandbox-base-image` raises it with the images (design D11 of own-git-invocation-policy); the version is judged by the same parser as the factory's own git check, and the reference sandbox-image recipe installs a git at or above it.

#### Scenario: An image whose git is below the floor is refused before the seed clone
- **WHEN** a box is materialized on an image whose `git --version` reports 2.43.0
- **THEN** the run stops with a message naming the image, the floor, the reported version and the remedy, one coded ERROR is logged, and no network, volume or container was created

#### Scenario: An image without git is refused with the runtime's complaint
- **WHEN** a box is materialized on an image in which `git` cannot be executed
- **THEN** the refusal quotes the runtime's answer instead of a version and nothing is created

#### Scenario: An image at or above the floor seeds as before
- **WHEN** a box is materialized on an image whose git reports the floor or newer
- **THEN** the probe logs nothing and the materialize continues with the seed clone

#### Scenario: A refused image releases the claimed task
- **WHEN** a claimed run stops because its box image's git is below the floor
- **THEN** the claim is released and the task is not aborted
