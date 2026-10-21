# The Linux spec runner's image (design D9 of make-pipe-reads-non-parking, as amended
# 2026-10-19; glossary: "Linux spec runner"). Run through scripts/linux-specs.sh, never by hand.
#
# It reproduces the platform of the CI `build` job (.github/workflows/ci.yml): Temurin 25 on
# Ubuntu, a git at or above the factory's floor (2.56, GitVersion.FLOOR) built from source by
# scripts/install-git.sh, the same script the CI jobs run (design D11 of
# own-git-invocation-policy), perl (the launcher of the process-group wrapper on macOS, present
# on CI's runner) and setsid (util-linux). It is deliberately NOT built FROM the sandbox base image of
# add-sandbox-base-image: that is a box image carrying the egress guard's proxy in its
# environment and mirrors the box, not CI.
#
# The build context is scripts/ (scripts/linux-specs.sh passes it, with this file as -f), so the
# install script is copied from there; the image tag hashes both files.
#
# No Docker CLI: the runner is for platform specs (:subprocess, :adapters:agent, the git
# runner); suites that need a Docker daemon stay on the host and on CI.
FROM eclipse-temurin:25-jdk-noble

COPY install-git.sh /tmp/install-git.sh

# One layer: the runtime packages, git built from the pinned source release, then every package
# the build alone needed purged. The libraries the installed git links against are marked manual
# first (the docker-library pattern), so the purge keeps them.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates perl rsync util-linux \
 && saved="$(apt-mark showmanual)" \
 && /tmp/install-git.sh --prefix /usr/local \
 && apt-mark auto '.*' >/dev/null \
 && apt-mark manual $saved >/dev/null \
 && find /usr/local/bin /usr/local/libexec/git-core -type f -executable -exec ldd '{}' ';' 2>/dev/null \
    | awk '/=>/ { so = $(NF-1); if (index(so, "/usr/local/") == 1) next; gsub("^/(usr/)?", "", so); print "*" so }' \
    | sort -u | xargs -r dpkg-query --search | cut -d: -f1 | sort -u | xargs -r apt-mark manual \
 && apt-get purge -y --auto-remove -o APT::AutoRemove::RecommendsImportant=false \
 && rm -rf /var/lib/apt/lists/* /tmp/install-git.sh

# Refuse an image whose git is below the floor, so a wrong build fails the image, not a spec.
RUN git version \
 && git version | awk '{ split($3, v, "."); exit !(v[1] > 2 || (v[1] == 2 && v[2] >= 56)) }' \
 && command -v setsid perl rsync

# The working-tree copy is mounted from the host and owned by the developer's uid, which has
# no entry in this image; trust it at system level, which the test build's own
# GIT_CONFIG_GLOBAL (the adversarial configuration) does not replace.
RUN git config --system --add safe.directory '*'
