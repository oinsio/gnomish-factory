## ADDED Requirements

### Requirement: The box image declares a matching contract version
Before creating a box, the container adapter SHALL read the configured sandbox image's labels and SHALL refuse to create the box when `io.gnomish.image-contract` is missing or differs from the contract version the factory was built with. The refusal SHALL be a configuration error raised before any round, naming the image, the found and the required version, and a `FROM ghcr.io/oinsio/gnomish-sandbox-base:<X.Y>` line to rebuild from. The labels SHALL be read by the same image inspection that already reads the image's declared volumes, so the check adds no runtime call of its own.
<!-- implements FR5, NFR-R1, NFR-O1, UX2 of add-sandbox-base-image -->

#### Scenario: Stale image refused with the fix
- **WHEN** the configured image carries `io.gnomish.image-contract=1` and the factory requires 2
- **THEN** no box is created, and the error names the image, versions 1 and 2, and the base tag to rebuild from

#### Scenario: Image not built on the base
- **WHEN** the configured image has no `io.gnomish.image-contract` label
- **THEN** no box is created, and the error says the image is not built on the base and how to fix it

#### Scenario: Matching image starts normally
- **WHEN** the configured image carries the required contract version
- **THEN** the box is created with exactly one image inspection
