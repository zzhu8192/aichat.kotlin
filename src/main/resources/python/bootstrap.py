"""kotllm's Python side: a persistent REPL driven over newline-delimited JSON.

Written to ./pyenv/kotllm_bootstrap.py and run from there, so it is inspectable
and editable by whoever is debugging it. Nothing here is kotllm-specific beyond
the protocol, and the protocol is deliberately boring.

Why newline-delimited JSON rather than length prefixes: json.dumps never emits a
literal newline (they come out as \\n inside strings), so one object per line is
unambiguous, and a capture of the stream is readable by eye. Length framing would
be marginally faster and much harder to debug.

Why values and paths travel as JSON rather than being interpolated into source:
there is nothing to escape. A Windows path full of backslashes, a string with
quotes in it, a nested dict -- all of it crosses as data. The JShell engine has
to build Java string literals for exactly this and needs an escaper; this does
not.

Three file descriptors, and the arrangement matters:

  * a **dup of fd 1**, taken before anything else, carries the protocol.
  * **fd 1 is then pointed at fd 2**, so a native write to stdout from a C
    extension -- which `contextlib.redirect_stdout` cannot intercept, because it
    only rebinds `sys.stdout` -- lands on stderr instead of corrupting the
    protocol stream.
  * `sys.stdout` / `sys.stderr` are captured per call and returned in the reply.
"""

import ast
import contextlib
import io
import json
import os
import sys
import tempfile
import traceback

_PROTO = os.fdopen(os.dup(1), "w", encoding="utf-8", newline="\n")
os.dup2(2, 1)

# The session. Everything the model defines lands here and stays for the life of
# the process, which is what makes read_to_var worth having.
#
# Seeded with the obvious stdlib, and that is not a convenience. `_exec` runs
# with globals=_NS, so a bare namespace means `json.dumps(x)` raises
# NameError -- and `json.dumps` is exactly what kotllm's own session_transfer
# contract tells the model to write. Same bug as the JShell API installing none
# of the default imports the jshell *tool* does, found the same way: the
# documented happy path failed.
import collections
import csv
import datetime
import functools
import itertools
import math
import pathlib
import random
import re
import statistics
import textwrap
import types

_NS = {
    "__name__": "__kotllm__",
    "__builtins__": __builtins__,
    "json": json,
    "os": os,
    "sys": sys,
    "re": re,
    "math": math,
    "csv": csv,
    "random": random,
    "pathlib": pathlib,
    "datetime": datetime,
    "collections": collections,
    "itertools": itertools,
    "functools": functools,
    "statistics": statistics,
    "textwrap": textwrap,
}

_MAX_REPR = 2000


def _send(obj):
    _PROTO.write(json.dumps(obj) + "\n")
    _PROTO.flush()


def _clip(text, limit=_MAX_REPR):
    if text is None:
        return None
    return text if len(text) <= limit else text[:limit] + "... [%d chars]" % len(text)


def _summarise(value):
    """Size and shape, never contents -- the same rule as the other engines.

    A listing that printed values would undo the reason for holding data out
    here in the first place.
    """
    try:
        if value is None:
            return "None"
        if isinstance(value, str):
            return "{:,} chars".format(len(value))
        if isinstance(value, (bytes, bytearray)):
            return "{:,} bytes".format(len(value))
        if isinstance(value, (list, tuple, set, frozenset, dict)):
            return "{:,} items".format(len(value))
        shape = getattr(value, "shape", None)
        if shape is not None:  # numpy, pandas
            return "shape {}".format(tuple(shape))
        text = repr(value)
        return text if len(text) <= 60 else "{:,} chars repr".format(len(text))
    except Exception:
        return "?"


def _exec(code):
    """Runs a block, returning the value of a trailing expression if there is one.

    This is what makes the tool feel like a REPL: `x = 1` prints nothing and
    `x + 1` reports 2. Splitting with `ast` rather than trying `eval` and falling
    back to `exec` means a block whose last line happens to be an expression
    still has its earlier statements executed exactly once.
    """
    tree = ast.parse(code, filename="<kotllm>", mode="exec")
    value = None
    if tree.body and isinstance(tree.body[-1], ast.Expr):
        last = ast.Expression(tree.body.pop().value)
        if tree.body:
            exec(compile(tree, "<kotllm>", "exec"), _NS)
        value = eval(compile(last, "<kotllm>", "eval"), _NS)
    else:
        exec(compile(tree, "<kotllm>", "exec"), _NS)
    return value


def _atomic_write(path, text):
    """`os.replace` is the atomic primitive on both POSIX and Windows.

    Same discipline as the JVM side: a temp file in the target's own directory
    (a rename across filesystems is not atomic), permissions carried over, then
    replace. `Files.write` truncating in place is what this avoids.
    """
    target = os.path.abspath(path)
    parent = os.path.dirname(target) or "."
    os.makedirs(parent, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=".kotllm-", suffix=".tmp", dir=parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as handle:
            handle.write(text)
        if os.path.exists(target):
            try:
                os.chmod(tmp, os.stat(target).st_mode & 0o7777)
            except OSError:
                pass
        os.replace(tmp, target)
        tmp = None
    finally:
        if tmp is not None and os.path.exists(tmp):
            os.unlink(tmp)
    return len(text)


def _handle(request):
    op = request.get("op")

    if op == "ping":
        return {"ok": True, "version": sys.version.split()[0], "executable": sys.executable}

    if op == "eval":
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            value = _exec(request["code"])
        rendered = None
        if value is not None:
            try:
                rendered = _clip(repr(value))
            except Exception:
                rendered = "<repr failed>"
        return {"ok": True, "stdout": _clip(out.getvalue(), 16000), "value": rendered}

    if op == "bind":
        # From the JVM as JSON, so anything JSON can carry crosses without a
        # single character of escaping.
        _NS[request["name"]] = request["value"]
        return {"ok": True, "summary": _summarise(_NS[request["name"]])}

    if op == "bind_file":
        # The child opens the file: the bytes never cross the boundary, which is
        # the whole point of read_to_var.
        with open(request["path"], "r", encoding="utf-8", errors="replace") as handle:
            _NS[request["name"]] = handle.read()
        return {"ok": True, "summary": _summarise(_NS[request["name"]])}

    if op == "get":
        # Python -> JVM. Whatever the expression evaluates to, as JSON, so the
        # JVM gets a real value rather than a printed one.
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            value = _exec(request["code"])
        try:
            encoded = json.dumps(value, default=str)
        except Exception as exc:
            return {"ok": False, "error": "value is not JSON-representable: %s" % exc}
        return {"ok": True, "json": encoded, "stdout": _clip(out.getvalue(), 4000)}

    if op == "text":
        # The counterpart of "write": same requirement, different destination.
        # The value comes back over the protocol rather than going to a file,
        # which is what a session-to-session transfer needs.
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            value = _exec(request["code"])
        if not isinstance(value, str):
            return {
                "ok": False,
                "error": "the expression produced %s, not str; JSON text is wanted here "
                         "(for example json.dumps(obj))" % type(value).__name__,
            }
        return {"ok": True, "text": value, "stdout": _clip(out.getvalue(), 4000)}

    if op == "write":
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            value = _exec(request["code"])
        # Python cannot refuse this at compile time the way Java and Scala do,
        # so the check is here and names the type it actually got -- otherwise a
        # list would be written as "['a', 'b']" and called a success.
        if not isinstance(value, str):
            return {
                "ok": False,
                "error": "the expression produced %s, not str; convert it first "
                         '(for example "\\n".join(rows), or json.dumps(obj))'
                         % type(value).__name__,
            }
        written = _atomic_write(request["path"], value)
        return {"ok": True, "written": written, "stdout": _clip(out.getvalue(), 4000)}

    if op == "vars":
        names = []
        for name, value in list(_NS.items()):
            if name.startswith("_"):
                continue
            # Modules by type rather than by name: the namespace is seeded with
            # a dozen of them and a hardcoded list would drift the moment one
            # was added, listing plumbing as the model's working set.
            if isinstance(value, types.ModuleType):
                continue
            if callable(value) and getattr(value, "__module__", "") == "builtins":
                continue
            names.append({"name": name, "type": type(value).__name__, "summary": _summarise(value)})
        names.sort(key=lambda item: item["name"])
        return {"ok": True, "vars": names}

    return {"ok": False, "error": "unknown op %r" % op}


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
        except Exception as exc:
            _send({"ok": False, "error": "bad request frame: %s" % exc})
            continue
        try:
            _send(_handle(request))
        except BaseException:
            # Tracebacks go back verbatim, the same contract as the compiler
            # diagnostics on the JVM side: a model fixes its own code from a
            # real NameError and cannot from "it failed".
            _send({"ok": False, "error": _clip(traceback.format_exc(), 8000)})


if __name__ == "__main__":
    main()
