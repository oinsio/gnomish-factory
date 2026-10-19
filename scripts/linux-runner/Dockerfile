# The Linux spec runner's image (design D9 of make-pipe-reads-non-parking, as amended
# 2026-10-19; glossary: "Linux spec runner"). Run through scripts/linux-specs.sh, never by hand.
#
# It reproduces the platform of the CI `build` job (.github/workflows/ci.yml): Temurin 25 on
# Ubuntu, a git at or above the factory's floor (2.51), perl (the launcher of the process-group
# wrapper on macOS, present on CI's runner) and setsid (util-linux). It is deliberately NOT
# built FROM the sandbox base image of add-sandbox-base-image: that is a box image carrying the
# egress guard's proxy in its environment and mirrors the box, not CI.
#
# No Docker CLI: the runner is for platform specs (:subprocess, :adapters:agent, the git
# runner); suites that need a Docker daemon stay on the host and on CI.
FROM eclipse-temurin:25-jdk-noble

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates gnupg software-properties-common \
 && add-apt-repository -y ppa:git-core/ppa \
 && apt-get update \
 && apt-get install -y --no-install-recommends git perl rsync util-linux \
 && apt-get purge -y software-properties-common \
 && apt-get autoremove -y \
 && rm -rf /var/lib/apt/lists/*

# Refuse an image whose git is below the floor, so a stale PPA fails the build, not a spec.
RUN git version \
 && git version | awk '{ split($3, v, "."); exit !(v[1] > 2 || (v[1] == 2 && v[2] >= 51)) }' \
 && command -v setsid perl rsync

# The working-tree copy is mounted from the host and owned by the developer's uid, which has
# no entry in this image; trust it at system level, which the test build's own
# GIT_CONFIG_GLOBAL (the adversarial configuration) does not replace.
RUN git config --system --add safe.directory '*'
