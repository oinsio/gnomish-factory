# Gnomish Factory

An orchestrator where AI coding agents take tasks from a task tracker and drive them through a
verifiable pipeline on their own; people step in only for escalations.

Project home and full documentation: <https://github.com/oinsio/gnomish-factory>

## What is in this folder

- `bin/gnomish` — the launcher. It finds Java 25 or newer (`JAVA_HOME`, else `java` on `PATH`)
  and passes every argument to the factory unchanged. Extra JVM options go in
  `GNOMISH_JAVA_OPTS`.
- `lib/` — the factory itself, one jar.
- `share/examples/` — example configuration files.
- `share/sandbox-image/` — the reference recipe for the container image the gnomes run in;
  its `README.md` says how to build it.
- `LICENSE`, `NOTICE` — the Apache License 2.0 and the attribution notice.

## Installing

You need Java 25 or newer and git. In the folder holding the downloaded archive and
`SHA256SUMS`:

```sh
sha256sum -c --ignore-missing SHA256SUMS     # macOS: shasum -a 256 -c --ignore-missing SHA256SUMS
gh attestation verify gnomish-<version>.tar.gz --repo oinsio/gnomish-factory   # optional
tar -xzf gnomish-<version>.tar.gz             # you may already have done this
export PATH="$PWD/gnomish-<version>/bin:$PATH"   # or add it to your shell profile
gnomish --version
```

If `gnomish` reports that Java 25 or newer is required, set `JAVA_HOME` to a Java 25
installation.

## Getting started

1. Register a clone of your project: `gnomish project add <name> --dir=<path-to-clone>`.
2. Run a task: `gnomish run --dir=<path-to-clone> --task="..."`, or serve the tracker's
   ready queue with `gnomish serve --dir=<path-to-clone>`.

The project's `README.md` describes the pipeline the factory expects under `.gnomish/` in the
target repository.
