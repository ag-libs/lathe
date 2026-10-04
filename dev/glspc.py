#!/usr/bin/env python3
"""
Headless harness that reproduces how a VS Code *generic LSP bridge* extension (e.g.
`zsol.vscode-glspc`, "Generic LSP Client") drives Lathe -- without booting VS Code.

A generic bridge adds nothing Lathe-specific: it spawns the configured `command` over stdio,
attaches it to a `languageId`, optionally sets `environmentVariables`, and forwards standard
JSON-RPC. This harness does exactly that against `lathe-launcher.sh`, so a green run means the
documented VS Code settings will work.

The one detail that bridges get right and a naive probe gets wrong: the server is launched with
its working directory set to the opened folder. Lathe's launcher resolves `.lathe/java-home`
*relative to cwd*, so GlspcClient sets `cwd = workspace root` -- the same path a VS Code user hits.

    # default: the multi-module invoker fixture (run `mvn verify -Dinvoker.test=multi-module` first)
    dev/glspc.py

    # any synced workspace; --java-home mirrors the glspc `environmentVariables` escape hatch
    dev/glspc.py --workspace /path/to/project --file src/main/java/app/Main.java
    dev/glspc.py --workspace ~/git/jdk --java-home ~/git/jdk/build/linux-x86_64-server-release/jdk \
                 --file src/java.base/share/classes/java/util/ArrayList.java

Exit code is 0 only if every capability check passes.
"""

import argparse
import os
import sys
from pathlib import Path

from lsp import LspClient, LATHE_LAUNCHER

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_WORKSPACE = REPO_ROOT / "lathe-maven-plugin/target/it/multi-module"
DEFAULT_FILE = "app/src/main/java/com/example/app/Main.java"


class GlspcClient(LspClient):
    """Generic-LSP-bridge preset: launch the server exactly as the VS Code extension would.

    Mirrors the glspc settings -- `server.command` (the launcher), `server.commandArguments`
    (none), `server.languageId` (java via didOpen), `server.environmentVariables` (env) -- and,
    crucially, launches with cwd = the opened folder so `.lathe/java-home` resolves."""

    @classmethod
    def start(cls, workspace_root, env_overrides: dict | None = None) -> "GlspcClient":
        root = Path(workspace_root).resolve()
        env = {**os.environ, **(env_overrides or {})}
        return cls._spawn([LATHE_LAUNCHER], env, root, cwd=root)

    def _handshake(self, root: Path):
        resp = self.request("initialize", {
            "processId": os.getpid(),
            "rootUri": root.as_uri(),
            "workspaceFolders": [{"uri": root.as_uri(), "name": root.name}],
            # The standard surface a generic bridge advertises -- no Lathe custom commands.
            "capabilities": {
                "textDocument": {
                    "publishDiagnostics": {"relatedInformation": True},
                    "hover": {"contentFormat": ["markdown", "plaintext"]},
                    "definition": {"linkSupport": True},
                    "references": {},
                    "completion": {"completionItem": {"snippetSupport": False}},
                    "documentSymbol": {"hierarchicalDocumentSymbolSupport": True},
                    "signatureHelp": {},
                },
                "workspace": {"symbol": {}},
                "window": {"workDoneProgress": True},
            },
        })
        self.notify("initialized", {})
        return resp


def locate(text: str, needle: str) -> tuple[int, int]:
    """0-based (line, col) of the first occurrence of `needle`. Probe-only text scan -- the server
    does the real analysis; this just aims the cursor the way a user would click."""
    for line_no, line in enumerate(text.splitlines()):
        col = line.find(needle)
        if col != -1:
            return line_no, col

    raise SystemExit(f"probe token {needle!r} not found in file")


# ── capability checks ───────────────────────────────────────────────────────────

class Check:
    def __init__(self):
        self.rows: list[tuple[str, bool, str]] = []

    def record(self, name: str, ok: bool, detail: str):
        self.rows.append((name, ok, detail))

    def run(self, name: str, fn):
        try:
            ok, detail = fn()
        except Exception as exc:
            ok, detail = False, f"{type(exc).__name__}: {exc}"

        self.record(name, ok, detail)

    def report(self) -> bool:
        width = max(len(n) for n, _, _ in self.rows)
        print()
        for name, ok, detail in self.rows:
            mark = "PASS" if ok else "FAIL"
            print(f"  [{mark}] {name.ljust(width)}  {detail}")

        passed = sum(1 for _, ok, _ in self.rows if ok)
        print(f"\n  {passed}/{len(self.rows)} checks passed")
        return passed == len(self.rows)


def smoke(client: GlspcClient, file: Path) -> bool:
    text = file.read_text()
    checks = Check()

    diagnostics = client.open(file)
    errors = [d for d in diagnostics if d.get("severity") == 1]
    checks.record(
        "diagnostics", not errors,
        f"{len(diagnostics)} diagnostic(s), {len(errors)} error(s)")

    su_line, su_col = locate(text, "StringUtils.upper")

    def check_hover():
        hover = client.hover(file, su_line, su_col)
        value = (hover or {}).get("contents", {})
        text_val = value.get("value") if isinstance(value, dict) else str(value)
        return bool(text_val), (text_val or "<empty>").splitlines()[0][:60]

    def check_definition():
        locs = client.definition(file, su_line, su_col)
        targets = [loc.get("targetUri") or loc.get("uri", "") for loc in locs]
        cross = [t for t in targets if "/core/" in t and "StringUtils.java" in t]
        return bool(cross), f"-> {Path(cross[0]).name if cross else targets}"

    def check_completion():
        # Position just after the `.` so the member list is in scope.
        dot = su_col + len("StringUtils")
        items = client.completion(file, su_line, dot + 1)
        labels = {i.get("label", "").split("(")[0] for i in items}
        return "upper" in labels, f"{len(items)} item(s), upper={'upper' in labels}"

    def check_document_symbol():
        syms = client.document_symbols(file)
        names = _symbol_names(syms)
        return "Main" in names, f"{sorted(names)}"

    def check_workspace_symbol():
        hits = client.workspace_symbol("StringUtils")
        names = {h.get("name", "") for h in hits}
        return "StringUtils" in names, f"{len(hits)} hit(s)"

    checks.run("hover", check_hover)
    checks.run("definition (cross-module)", check_definition)
    checks.run("completion", check_completion)
    checks.run("documentSymbol", check_document_symbol)
    checks.run("workspaceSymbol", check_workspace_symbol)
    return checks.report()


def _symbol_names(symbols: list[dict]) -> set[str]:
    names: set[str] = set()
    for sym in symbols:
        names.add(sym.get("name", ""))
        names |= _symbol_names(sym.get("children", []))

    return names


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--workspace", default=str(DEFAULT_WORKSPACE),
                        help="opened folder (default: multi-module invoker fixture)")
    parser.add_argument("--file", default=DEFAULT_FILE,
                        help="Java file to probe, relative to --workspace (or absolute)")
    parser.add_argument("--java-home",
                        help="set LATHE_JAVA_HOME (the glspc environmentVariables escape hatch)")
    args = parser.parse_args()

    workspace = Path(args.workspace).resolve()
    if not (workspace / ".lathe").is_dir():
        print(f"no .lathe/ under {workspace} -- run `mvn ... :sync` (or the invoker) first",
              file=sys.stderr)
        return 2

    file = Path(args.file)
    if not file.is_absolute():
        file = workspace / file

    env_overrides = {"LATHE_JAVA_HOME": args.java_home} if args.java_home else None

    print(f"launcher:  {LATHE_LAUNCHER}")
    print(f"workspace: {workspace}  (cwd of the server)")
    print(f"file:      {file.relative_to(workspace) if file.is_relative_to(workspace) else file}")

    with GlspcClient.start(workspace, env_overrides) as client:
        ok = smoke(client, file)

    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
