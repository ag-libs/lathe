#!/usr/bin/env python3
"""
burst.py — replay an editor's rename-and-save-all burst against a headless Lathe server and
report what the server did, for before/after comparisons of batching changes.

    python3 dev/burst.py <workspace> <file> <symbol> <new-name> [options]

Replays the Neovim pattern of `grn` + `:wa`:
  1. rename <symbol> (its first declaration line in <file>) to <new-name>;
  2. apply the WorkspaceEdit as Neovim does: didOpen + didChange per touched file, and for a
     file move, move the (unsaved, old-content) file on disk then didClose old + didOpen new;
  3. after --think seconds, save every touched file in open order: format request, apply its
     edits as a didChange, write to disk, didSave, then wait --pace seconds (0 = Save All).
On every workspace/semanticTokens/refresh it re-requests the tokens of the renamed file, as an
editor repaints its visible window. Then it waits until the server is quiet and prints counts.

The edit is written to disk; run it again with the names swapped to rename back, e.g.
    burst.py /tmp/ev-smoke .../EqualsVerifier.java EqualsVerifier EqualsVerifierXXX
    burst.py /tmp/ev-smoke .../EqualsVerifierXXX.java EqualsVerifierXXX EqualsVerifier
Uses the working-tree SNAPSHOT server (see lsp.py); rebuild before measuring.
"""

import argparse
import collections
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from lsp import LatheClient  # noqa: E402

COMPILE = re.compile(r"INFO +\[compile:([\w-]+)\]")
COUNTED = {
    "react batches": re.compile(r"\[react\] recompiled"),
    "dependents scheduled": re.compile(r"\[dependents\] recompiling"),
    "cache evictions": re.compile(r"\[evict\]"),
}
ACTIVITY = re.compile(r"\[(compile:\w+|react|dependents)\]")


def utf16_index(line: str, character: int) -> int:
    units = 0
    for index, ch in enumerate(line):
        if units >= character:
            return index
        units += 2 if ord(ch) > 0xFFFF else 1
    return len(line)


def offset(content: str, position: dict) -> int:
    lines = content.splitlines(keepends=True)
    row = position["line"]
    if row >= len(lines):
        return len(content)
    return sum(len(l) for l in lines[:row]) + utf16_index(lines[row], position["character"])


def apply_edits(content: str, edits: list[dict]) -> str:
    spans = sorted(
        ((offset(content, e["range"]["start"]), offset(content, e["range"]["end"]), e["newText"])
         for e in edits),
        reverse=True,
    )
    for start, end, text in spans:
        content = content[:start] + text + content[end:]
    return content


def uri_path(uri: str) -> Path:
    from urllib.parse import unquote, urlparse
    return Path(unquote(urlparse(uri).path))


class Editor:
    """The open buffers, keyed by URI, as the editor holds them."""

    def __init__(self, client: LatheClient):
        self.client = client
        self.docs: dict[str, dict] = {}
        self.original: dict[str, str] = {}

    def adopt(self, uri: str):
        """Tracks a document the client already opened."""
        self.docs[uri] = {"text": uri_path(uri).read_text(), "version": 1}
        self.original[uri] = self.docs[uri]["text"]

    def ensure_open(self, uri: str):
        if uri in self.docs:
            return
        self.adopt(uri)
        self.did_open(uri)

    def change(self, uri: str, text: str):
        doc = self.docs[uri]
        doc["text"] = text
        doc["version"] += 1
        self.client.notify("textDocument/didChange", {
            "textDocument": {"uri": uri, "version": doc["version"]},
            "contentChanges": [{"text": text}],
        })

    def move(self, old_uri: str, new_uri: str):
        """Neovim renames the buffer in place, so the moved file keeps its save position."""
        uri_path(old_uri).rename(uri_path(new_uri))
        self.original[new_uri] = self.original.pop(old_uri)
        self.docs = {new_uri if uri == old_uri else uri: {"text": d["text"], "version": 1}
                     if uri == old_uri else d for uri, d in self.docs.items()}
        self.client.notify("textDocument/didClose", {"textDocument": {"uri": old_uri}})
        self.did_open(new_uri)

    def did_open(self, uri: str):
        self.client.notify("textDocument/didOpen", {"textDocument": {
            "uri": uri, "languageId": "java", "version": 1, "text": self.docs[uri]["text"]}})

    def save(self, uri: str, format_on_save: bool):
        if format_on_save:
            edits = self.client.formatting(uri_path(uri))
            if edits:
                self.change(uri, apply_edits(self.docs[uri]["text"], edits))
        uri_path(uri).write_text(self.docs[uri]["text"])
        self.client.notify("textDocument/didSave", {"textDocument": {"uri": uri}})


def apply_workspace_edit(editor: Editor, edit: dict) -> str | None:
    """Applies the edit as Neovim does; returns the moved file's new URI, if any."""
    moved_to = None
    for change in edit.get("documentChanges") or []:
        if change.get("kind") == "rename":
            editor.move(change["oldUri"], change["newUri"])
            moved_to = change["newUri"]
            continue
        uri = change["textDocument"]["uri"]
        editor.ensure_open(uri)
        editor.change(uri, apply_edits(editor.docs[uri]["text"], change["edits"]))
    for uri, edits in (edit.get("changes") or {}).items():
        editor.ensure_open(uri)
        editor.change(uri, apply_edits(editor.docs[uri]["text"], edits))
    return moved_to


class TokenRepaint:
    """Neovim's answer to a token refresh: re-request the visible window's tokens, keeping at most
    one request in flight per buffer (a newer refresh cancels the pending one)."""

    def __init__(self, client: LatheClient, visible: list[str]):
        self.client = client
        self.visible = visible
        self.pending = None

    def on_request(self, msg: dict):
        if msg["method"] != "workspace/semanticTokens/refresh":
            return
        if self.pending is not None and self.pending.responses.empty():
            self.pending.cancel()
        self.pending = self.client.request_async(
            "textDocument/semanticTokens/full", {"textDocument": {"uri": self.visible[0]}})


def wait_quiet(client: LatheClient, quiet_s: float, max_s: float) -> float:
    """Waits until no compile/react/dependents line for quiet_s; returns when activity ended. A rename
    commit's batch can compile for longer than quiet_s without logging, so once a commit is logged the
    wait first lasts until its dependents refresh starts."""
    start = time.monotonic()
    seen = len(client.stderr_lines)
    while time.monotonic() - start < max_s:
        lines = client.stderr_lines
        commit = next((i for i in range(len(lines) - 1, -1, -1) if "[rename-tx] commit" in lines[i]), None)
        if commit is None or any("[dependents] checking" in l for l in lines[commit:]):
            break
        time.sleep(0.25)
    last_activity = time.monotonic()
    while time.monotonic() - start < max_s:
        time.sleep(0.25)
        lines = client.stderr_lines[seen:]
        seen += len(lines)
        if any(ACTIVITY.search(l) for l in lines):
            last_activity = time.monotonic()
        if time.monotonic() - last_activity >= quiet_s:
            break
    return last_activity


def declaration(file: Path, symbol: str) -> tuple[int, int]:
    pattern = re.compile(r"\b(class|interface|enum|record)\s+%s\b" % re.escape(symbol))
    for row, line in enumerate(file.read_text().splitlines()):
        match = pattern.search(line)
        if match:
            return row, line.index(symbol, match.start())
    raise SystemExit("no declaration of %s in %s" % (symbol, file))


def last_publishes(client: LatheClient) -> dict[str, list]:
    """The last diagnostics published per URI (drains the client's publish queue)."""
    q = client._notification_queue("textDocument/publishDiagnostics")
    last = {}
    while not q.empty():
        params = q.get_nowait()["params"]
        last[params["uri"]] = params["diagnostics"]
    return last


def mirror_class(workspace: Path, source: Path) -> Path:
    """The .lathe mirror class file a top-level type's source compiles to."""
    rel = source.relative_to(workspace)
    parts = rel.parts
    i = parts.index("src")
    tree = "test-classes" if parts[i + 1] == "test" else "classes"
    pkg = Path(*parts[i + 3:]).with_suffix(".class")
    return workspace / ".lathe" / Path(*parts[:i]) / tree / pkg


def check(editor: Editor, client: LatheClient, workspace: Path, moved: tuple | None,
          old: str, new: str) -> bool:
    """End-of-burst correctness: no member still erroring, and for a moved type the mirror and the
    workspace symbols hold only the new name."""
    last = last_publishes(client)
    erroring = sorted(uri_path(uri).name for uri in editor.docs
                      if any(d.get("severity") == 1 for d in last.get(uri, [])))
    ok = not erroring
    print("  %-28s %6d  %s" % ("members erroring at end", len(erroring), " ".join(erroring[:5])))
    if moved:
        old_class, new_class = (mirror_class(workspace, uri_path(u)) for u in moved)
        # Only the workspace's own types: a JDK or dependency type may share the old name.
        names = {s["name"] for s in client.workspace_symbol(new) + client.workspace_symbol(old)
                 if uri_path(s["location"]["uri"]).is_relative_to(workspace)}
        mirror_ok = new_class.exists() and not old_class.exists()
        symbols_ok = new in names and old not in names
        ok = ok and mirror_ok and symbols_ok
        print("  %-28s %6s  old=%s new=%s" % ("mirror swapped", mirror_ok, old_class.exists(),
                                              new_class.exists()))
        print("  %-28s %6s  %s" % ("symbols swapped", symbols_ok,
                                   sorted(n for n in names if n in (old, new))))
    print("CHECKS:", "ok" if ok else "FAIL")
    return ok


def saw(client: LatheClient, first_line: int, needle: str) -> bool:
    return any(needle in line for line in client.stderr_lines[first_line:])


def report(client: LatheClient, first_line: int, times: dict):
    lines = client.stderr_lines[first_line:]
    compiles = collections.Counter(m.group(1) for l in lines for m in [COMPILE.search(l)] if m)
    print("\n=== burst report ===")
    for label, seconds in times.items():
        print("  %-28s %6.1fs" % (label, seconds))
    print("  %-28s %6d  %s" % ("compiles", sum(compiles.values()),
                               " ".join("%s=%d" % kv for kv in sorted(compiles.items()))))
    for label, pattern in COUNTED.items():
        print("  %-28s %6d" % (label, sum(1 for l in lines if pattern.search(l))))
    print("  %-28s %6d" % ("publishDiagnostics",
                           client.notification_counts["textDocument/publishDiagnostics"]))
    print("  %-28s %6d" % ("semanticTokens/refresh",
                           client.server_request_counts["workspace/semanticTokens/refresh"]))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("workspace", type=Path)
    parser.add_argument("file", type=Path)
    parser.add_argument("symbol")
    parser.add_argument("new_name")
    parser.add_argument("--pace", type=float, default=0.55,
                        help="seconds between saves (Neovim :wa ~0.55; 0 = Save All)")
    parser.add_argument("--think", type=float, default=3.0,
                        help="seconds between applying the edit and the first save")
    parser.add_argument("--warmup", type=float, default=12.0,
                        help="seconds to let the reference index build before renaming")
    parser.add_argument("--no-format", action="store_true", help="skip format-on-save")
    parser.add_argument("--quiet", type=float, default=5.0,
                        help="seconds without compile activity that count as settled")
    parser.add_argument("--declaring-last", action="store_true",
                        help="save the renamed file last (an editor that saves dependents first)")
    parser.add_argument("--at", help="1-based LINE:COL of the symbol (default: its type declaration)")
    parser.add_argument("--save-count", type=int,
                        help="save only the first N touched files (the rest stay unsaved)")
    parser.add_argument("--no-apply", action="store_true",
                        help="never apply the edit (a cancelled preview); expect the transaction dropped")
    parser.add_argument("--undo", action="store_true",
                        help="apply, then undo every file instead of saving; expect it abandoned")
    parser.add_argument("--log", type=Path, help="write the server's stderr for the burst here")
    args = parser.parse_args()

    file = args.file.resolve()
    if args.at:
        line, column = (int(n) for n in args.at.split(":"))
        row, col = line - 1, column - 1
    else:
        row, col = declaration(file, args.symbol)
    with LatheClient.start(args.workspace, debug=True) as client:
        client.open(file, timeout=120)
        time.sleep(args.warmup)
        edit = client.rename(file, row, col, args.new_name)
        if not edit:
            raise SystemExit("rename returned no edit")

        editor = Editor(client)
        editor.adopt(file.as_uri())
        visible = [file.as_uri()]
        tokens = TokenRepaint(client, visible)
        client.on_server_request = tokens.on_request
        first_line = len(client.stderr_lines)
        try:
            client.notification_counts.clear()
            client.server_request_counts.clear()

            t0 = time.monotonic()
            if args.no_apply:
                time.sleep(35)
                dropped = saw(client, first_line, "[rename-tx] dropped unapplied")
                print("CHECKS:", "ok" if dropped else "FAIL", "(transaction dropped unapplied: %s)" % dropped)
                return

            moves = [(c["oldUri"], c["newUri"]) for c in edit.get("documentChanges") or []
                     if c.get("kind") == "rename"]
            moved_to = apply_workspace_edit(editor, edit)
            if moved_to:
                visible[0] = moved_to
            print("applied edit to %d file(s)%s" % (
                len(editor.docs), ", moved %s" % uri_path(moved_to).name if moved_to else ""))
            time.sleep(args.think)

            if args.undo:
                for uri in list(editor.docs):
                    editor.change(uri, editor.original[uri])
                time.sleep(2)
                abandoned = saw(client, first_line, "[rename-tx] abandoned")
                print("CHECKS:", "ok" if abandoned else "FAIL", "(transaction abandoned: %s)" % abandoned)
                return

            t_save = time.monotonic()
            order = list(editor.docs)
            if args.declaring_last:
                order.remove(visible[0])
                order.append(visible[0])
            for uri in order[:args.save_count]:
                editor.save(uri, not args.no_format)
                time.sleep(args.pace)
            t_saved = time.monotonic()
            print("saved %d file(s)" % len(order[:args.save_count]))

            if args.save_count is not None:
                time.sleep(12)  # past the server's 10 s idle commit for a partly saved rename
            settled = wait_quiet(client, args.quiet, 600)
            report(client, first_line, {
                "save phase": t_saved - t_save,
                "quiet after last save": max(0.0, settled - t_saved),
                "total (edit to quiet)": settled - t0,
            })
            if args.save_count is None:
                check(editor, client, args.workspace.resolve(), moves[0] if moves else None,
                      args.symbol, args.new_name)
            else:
                committed = saw(client, first_line, "[rename-tx] commit saved=%d" % args.save_count)
                print("CHECKS:", "ok" if committed else "FAIL",
                      "(idle commit of the %d saved: %s)" % (args.save_count, committed))
        finally:
            if args.log:
                args.log.write_text("\n".join(client.stderr_lines[first_line:]) + "\n")


if __name__ == "__main__":
    main()
