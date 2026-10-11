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

COMPILE = re.compile(r"INFO +\[compile:(\w+)\]")
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

    def adopt(self, uri: str):
        """Tracks a document the client already opened."""
        self.docs[uri] = {"text": uri_path(uri).read_text(), "version": 1}

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
    """Waits until no compile/react/dependents line for quiet_s; returns when activity ended."""
    start = time.monotonic()
    seen = len(client.stderr_lines)
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
    parser.add_argument("--log", type=Path, help="write the server's stderr for the burst here")
    args = parser.parse_args()

    file = args.file.resolve()
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
        client.notification_counts.clear()
        client.server_request_counts.clear()

        t0 = time.monotonic()
        moved_to = apply_workspace_edit(editor, edit)
        if moved_to:
            visible[0] = moved_to
        print("applied edit to %d file(s)%s" % (
            len(editor.docs), ", moved %s" % uri_path(moved_to).name if moved_to else ""))
        time.sleep(args.think)

        t_save = time.monotonic()
        order = list(editor.docs)
        if args.declaring_last:
            order.remove(visible[0])
            order.append(visible[0])
        for uri in order:
            editor.save(uri, not args.no_format)
            time.sleep(args.pace)
        t_saved = time.monotonic()
        print("saved %d file(s)" % len(editor.docs))

        settled = wait_quiet(client, args.quiet, 600)
        report(client, first_line, {
            "save phase": t_saved - t_save,
            "quiet after last save": max(0.0, settled - t_saved),
            "total (edit to quiet)": settled - t0,
        })
        if args.log:
            args.log.write_text("\n".join(client.stderr_lines[first_line:]) + "\n")


if __name__ == "__main__":
    main()
