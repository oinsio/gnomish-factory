# Tasks: add-sandbox-cloud-executor

Order follows the migration plan (design): lifecycle against the
contract suite first, then git transport and the cache invariant, then
egress, then the Docker ladder, secrets and cost controls last.
Requires change A (`add-sandbox-core`) implemented; composes with
change B; independent of changes C and E. Cluster-touching specs run
against a disposable in-CI cluster (k3s-class via Testcontainers) and
are gated on its availability.

## 1. Spikes and re-verification

- [ ] 1.1 Disposable-cluster fidelity spike: k3s/kind via Testcontainers, NetworkPolicy default-deny enforcement — decides where egress E2E runs (Q2, D9)
- [ ] 1.2 kubedock re-check: API coverage, bind-mount gaps, project health (Q3, D5)
- [ ] 1.3 k8s client choice: official Java client vs fabric8 vs raw HTTP on the existing stack; Resilience4j wrapping (design decision, Impact)
- [ ] 1.4 OIDC trust spike: in-cluster vs out-of-cluster factory against OpenBao (Q5, D6)

## 2. Adapter core

- [ ] 2.1 Factory config surface: kubeconfig/context, namespace prefix, quotas/limits, RuntimeClass bindings, registry endpoint (FR4, FR8, UX1)
- [ ] 2.2 Namespace-per-task and pod-per-environment lifecycle with factory labels; dispose idempotent (pod / namespace levels) (FR1)
- [ ] 2.3 Exec via the Kubernetes exec API: streamed output + exit codes through the port (FR1)
- [ ] 2.4 RBAC manifest shipped and used; least-privilege spec (FR11, D7)
- [ ] 2.5 Port-level contract suite green on the k8s adapter (cluster-gated) (M1)

## 3. Git transport and cache invariant

- [ ] 3.1 Seed and harvest over git transport into/out of the environment pod; fixed-refspec, fast-forward-only semantics preserved (FR1)
- [ ] 3.2 PVC warm-resume cache: attach, reuse within task, delete on dispose by default (FR5, D3)
- [ ] 3.3 E2E: PVC deleted mid-task → resume from branch completes correctly (M3)
- [ ] 3.4 E2E: second instance resumes an interrupted cloud task (M5-part)

## 4. Egress

- [ ] 4.1 Default-deny NetworkPolicy per task namespace; guard-only egress incl. DNS; explicit metadata-endpoint denial (FR2, D2)
- [ ] 4.2 In-cluster guard deployment (same mitmproxy image/config); per-namespace topology first, measure for Q1 (FR2, D2, Q1)
- [ ] 4.3 Self-check probes: direct egress, metadata endpoint, guard path, RuntimeClass match; failure = infrastructure failure (FR3, D4)
- [ ] 4.4 Denials (NetworkPolicy + guard) into the existing findings funnel; distinguishable in the report (NFR-O1, UX3)
- [ ] 4.5 Cluster registry policy: checklist item + verification step in docs (FR8, D2)

## 5. Docker ladder

- [ ] 5.1 Shared neighbor-stack declaration in the stage `Mechanism`: subset schema (name, image, env, exposed ports; Q6 fields decided against concrete stacks), law-sourced loading, fail-closed filter (privileged / host mounts outside workspace / published ports / socket mounts / cap adds) as located `ConfigError`s; loader specs (FR12, D10, Q6)
- [ ] 5.2 Local realization in the container adapter: factory-labeled service containers joined to the task internal network, started before the stage, disposed with the segment; resource limits, orphan sweep, registry-parameter image resolution; reachable by declared name only (FR13, D10)
- [ ] 5.3 k8s realization: the same declaration rendered as pods in the task namespace under policy/quota; forbidden-mechanism refusal (privileged DinD, node sockets) (FR6, D5, D10)
- [ ] 5.4 kubedock integration: emulator endpoint in the task namespace, DOCKER_HOST wiring, quota/policy inheritance (FR6, D5)
- [ ] 5.5 RuntimeClass dial for sysbox/Kata; passport declarations per level (FR4, FR7, D4)
- [ ] 5.6 E2E: Testcontainers check passes via kubedock under default-deny (M4)
- [ ] 5.7 E2E (local, Docker-gated): check reaches its declared neighbor by name in the internal network with direct egress blocked; forbidden declarations refused naming the mechanism (M7)

## 6. Secrets, cost, docs

- [ ] 6.1 OpenBao adapter for the SecretsProvider port; consumer-invisible swap spec (FR9, D6)
- [ ] 6.2 OIDC bootstrap flow per 1.4 verdict; no static secrets in cluster resources; E2E assertion (FR9, NFR-S1, M6)
- [ ] 6.3 Orphan reclaim cluster-wide by labels: namespaces, pods, PVCs; age-logged, concurrent-safe (FR10, NFR-R2, NFR-C1, M5)
- [ ] 6.4 E2E: metadata + direct egress fail, allowlisted via guard passes (M2)
- [ ] 6.5 Docs: cloud checklist (NetworkPolicy verification, registry policy, RuntimeClass trade-offs, data-residency statement, volume-is-cache rule), prerequisites, RBAC install (UX1, UX2, UX4)
