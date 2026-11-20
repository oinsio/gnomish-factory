# Community plugin registry

<!-- implements FR14, M5 of add-project-license -->

Plugins built by others against the published plugin contract,
[`gnomish-plugin-api`](../gnomish-plugin-api/README.md). The tracker is the pluginizable port, so
every plugin listed here is a tracker adapter. A verification check is not a plugin: it is a
verdict program in any language that speaks the executor contract, declared in a target
repository's `.gnomish/` and proven with `gnomish executor verify` (the conformance kit,
[ADR 0020](adr/0020-executor-contract.md)), so it needs no row here to be used.

> **Listed as-is.** The project does not vet, review, test or endorse the plugins below. A
> listed plugin is not part of Gnomish Factory and is not covered by its Apache License 2.0: it
> is distributed by its maintainer under the license its row states. Read that license, and the
> plugin's source, before you run it.

## Announce a plugin

Open an issue from the
[plugin-announcement form](https://github.com/oinsio/gnomish-factory/issues/new?template=plugin-announcement.yml)
and fill in its five fields. The maintainer adds the row from the issue as it stands; you need
no pull request and no git operation. The form's field ids are the table's columns, one to one.

How to build a plugin is in the [adapter author guide](guides/adapter-author-guide.md).

## Plugins

| name                        | link                                                      | adapts                                         | maintainer                  | license    |
|-----------------------------|-----------------------------------------------------------|------------------------------------------------|-----------------------------|------------|
| `gnomish-plugin-api:sample` | [gnomish-plugin-api/sample](../gnomish-plugin-api/sample) | tracker and verdict program (in-repo stand-in) | The Gnomish Factory Authors | Apache-2.0 |

The first row is not a third-party plugin: it is the in-repository sample that proves the
tracker contract compiles on its own and ships a sample verdict program that passes the
conformance kit, listed to show the row format. It stays until the first real
announcement arrives.
