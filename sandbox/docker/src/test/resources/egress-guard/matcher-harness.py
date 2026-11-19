"""Runs the egress guard addon's own matcher outside mitmproxy (EgressGuardMatcherIdentitySpec).

Arguments: the addon script's text, the rendered allowlist JSON array, then the host names. Prints
one line per name, in order: "true" or "false", the addon's _allowed answer for it.

The addon imports mitmproxy and reads its allowlist from the guard's config mount at import time;
both are stood in for here, so the matcher that runs is exactly the addon's text, unmodified.
"""

import builtins
import io
import sys
import types

script, allowlist, names = sys.argv[1], sys.argv[2], sys.argv[3:]

mitmproxy = types.ModuleType("mitmproxy")
mitmproxy.http = types.SimpleNamespace(Response=None)
sys.modules["mitmproxy"] = mitmproxy

_real_open = builtins.open


def _open(path, *args, **kwargs):
    if path == "/gnomish-guard/allowlist.json":
        return io.StringIO(allowlist)
    return _real_open(path, *args, **kwargs)


builtins.open = _open
namespace = {"__name__": "guard"}
exec(compile(script, "guard.py", "exec"), namespace)
builtins.open = _real_open

for name in names:
    print("true" if namespace["_allowed"](name) else "false")
