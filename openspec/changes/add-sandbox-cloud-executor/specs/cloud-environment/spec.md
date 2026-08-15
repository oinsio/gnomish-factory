# cloud-environment

## ADDED Requirements

### Requirement: k8s adapter of the environment port
A `k8s` adapter SHALL implement the `TaskExecutionEnvironment` port:
one namespace per task, one pod per environment from the resolved OCI
image, exec via the Kubernetes exec API with streamed output and exit
codes, harvest over git transport with factory-fixed refspec semantics.
`dispose()` SHALL tear down the environment's pod — and at task end the
namespace with everything in it — as one idempotent operation.
<!-- implements FR1 of add-sandbox-cloud-executor -->

#### Scenario: One task, one namespace
- **WHEN** a task is bound to the k8s adapter and its environment is materialized
- **THEN** a factory-labeled namespace holds the environment pod, and task-end disposal removes the namespace and all its contents

#### Scenario: Rounds run unchanged above the port
- **WHEN** an agent round or command check executes in a cloud environment
- **THEN** its consumers receive the same streamed output and exit-code contract as with every other adapter

### Requirement: Resource bounds and RuntimeClass per binding
Operator-configured ResourceQuota and LimitRange SHALL bound every task
namespace; the runtime knob SHALL map to RuntimeClass per binding, and
the pod's effective RuntimeClass SHALL be verified against the
binding's declaration before the first round.
<!-- implements FR3, FR4 of add-sandbox-cloud-executor -->

#### Scenario: Hardened binding gets the hardened runtime
- **WHEN** a binding declares a VM-grade RuntimeClass and the cluster schedules the pod without it
- **THEN** the self-check fails the RuntimeClass probe and the task does not start

#### Scenario: A runaway task stays inside its quota
- **WHEN** a build inside the pod attempts to exceed the namespace quota
- **THEN** the excess is refused by the cluster and surfaces as a normal failure of that task only

### Requirement: Volume is cache, never truth
A PVC MAY cache the working clone and dependencies for warm resume;
deleting any task volume at any moment SHALL never lose correctness —
durable state remains the tracker and the task branch only. Disposal
SHALL delete task volumes by default; retained volumes are a
warm-resume optimization subject to orphan cleanup.
<!-- implements FR5 of add-sandbox-cloud-executor -->

#### Scenario: Volume loss costs time, not correctness
- **WHEN** a task's PVC is deleted mid-task (node loss, manual deletion)
- **THEN** the factory materializes a fresh environment from the branch state and the task completes correctly

### Requirement: In-box Docker via the k8s ladder
In-box Docker needs SHALL be served without privileged containers and
without exposing any node socket: neighbor service stacks render the
shared neighbor-stack declaration (see the execution-environment
delta) as pods in the task namespace under its NetworkPolicy and
quota; dynamic
Testcontainers workloads run via a Docker-API emulator (kubedock-class)
creating pods in the same namespace; dockerd-in-pod requires a
sysbox/Kata RuntimeClass. Privileged DinD sidecars and node CRI/Docker
socket mounts SHALL be refused always.
<!-- implements FR6 of add-sandbox-cloud-executor -->

#### Scenario: Testcontainers under default-deny
- **WHEN** a check uses Testcontainers against the emulated Docker endpoint in a task namespace
- **THEN** the containers it creates are pods in that namespace, bounded by its quota and NetworkPolicy, and the check passes

#### Scenario: Privileged escape hatch is refused
- **WHEN** a stage or repo configuration requests a privileged DinD sidecar or a node socket mount
- **THEN** the factory refuses fail-closed naming the forbidden mechanism

### Requirement: Cloud adapter passport
The k8s passport SHALL declare: task↔task boundary (namespace + node
kernel, upgradeable to VM-grade via RuntimeClass), in-box Docker
support level, NetworkPolicy-enforced egress, and the cost profile
(quota-bounded, cluster-billed); it SHALL state that task data leaves
the operator's machine — a trust and jurisdiction decision.
Reconciliation SHALL route stage needs against it fail-closed.
<!-- implements FR7, NFR-S2 of add-sandbox-cloud-executor -->

#### Scenario: Needs meet the passport
- **WHEN** a stage requiring VM-grade isolation is bound to the k8s adapter without a hardened RuntimeClass configured
- **THEN** reconciliation refuses fail-closed naming the unmet need

### Requirement: Namespaced RBAC, no cluster-admin
The factory SHALL operate under a shipped RBAC role scoped to
factory-labeled namespaces and the resources the adapter manages;
cluster-admin SHALL NOT be required or requested.
<!-- implements FR11 of add-sandbox-cloud-executor -->

#### Scenario: Least privilege holds
- **WHEN** the factory's service account is inspected
- **THEN** its permissions cover only the adapter's resource types, and destructive operations are possible only on factory-labeled resources

### Requirement: Orphan cleanup as cost control
Startup SHALL find and destroy factory-labeled namespaces, pods, and
volumes whose task is no longer live, cluster-wide, idempotently and
safely alongside concurrent instances; reclaim events SHALL be logged
with resource age and count. <!-- implements FR10, NFR-C1 of add-sandbox-cloud-executor -->

#### Scenario: A crashed instance stops costing money
- **WHEN** a factory instance dies leaving task namespaces running and another instance starts
- **THEN** orphaned namespaces are destroyed, the reclaim is logged, and interrupted tasks remain resumable from their branches
