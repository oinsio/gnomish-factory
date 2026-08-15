# sandbox-provisioning (delta)

## ADDED Requirements

### Requirement: VM provisioning phase without snapshots
For VM-bound projects, `.gnomish/setup.sh` SHALL execute inside the VM
during its provisioning phase — before any gnome process, under the
same egress policy, read from the factory's law clone. The post-setup
snapshot cache SHALL remain container-only: no snapshot of a VM is
taken in any flow, and the environment port remains snapshot-free, so a
gnome-touched VM can never be persisted. VM provisioning cost is paid
per environment.
<!-- implements FR10 of add-sandbox-colima-vm -->

#### Scenario: setup.sh runs before the gnome, inside the cage
- **WHEN** a VM environment is provisioned for a project with `.gnomish/setup.sh`
- **THEN** the script runs inside the VM with egress limited to the guard endpoints, and completes before the first gnome process starts

#### Scenario: No VM snapshot exists to poison
- **WHEN** a task completes in a VM environment
- **THEN** no image, template, or snapshot derived from that VM remains; the next environment provisions from the stock template
