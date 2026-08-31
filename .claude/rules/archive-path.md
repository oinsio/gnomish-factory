# Rule: archive path structure

When archiving OpenSpec changes, group by year and month:

```
openspec/changes/archive/YYYY/MM/YYYY-MM-DD-<change-name>
```

Instead of the default flat structure `openspec/changes/archive/YYYY-MM-DD-<change-name>`.

If the change name already starts with a `YYYY-MM-DD-` prefix, use it as-is — never stack
a second date (same rule as `openspec archive`); the year/month directories come from that
existing prefix.

Example for a change archived on 2026-07-14:

```bash
mkdir -p openspec/changes/archive/2026/07
mv openspec/changes/<name> openspec/changes/archive/2026/07/2026-07-14-<name>
```
