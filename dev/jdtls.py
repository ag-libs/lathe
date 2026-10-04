#!/usr/bin/env python3
"""
jdtls (Eclipse JDT Language Server) preset for differential testing against Lathe.

Reuses the generic transport and LSP feature helpers from `lsp.LspClient`; adds only the
jdtls-specific launch, handshake, and import-readiness wait.

Key facts (see docs/planned/lathe-jdtls-differential-testing.md):
  * jdtls is an Eclipse/OSGi app whose bundled ASM cannot weave class files newer than it
    supports, so it must RUN on a JDK <= its ceiling. jdtls 1.51 runs on JDK 21..25, NOT 26
    (major version 70). We launch it on Corretto 25, which also unlocks the optional
    org.eclipse.jdt.core.javac plugin and lets it analyse source up to Java 25.
  * A per-workspace -data directory caches the project import; reusing it makes restarts fast.
  * jdtls signals import completion with a `language/status` notification {type:"Started"}.

    from jdtls import JdtlsClient
    with JdtlsClient.start("/path/to/project") as c:
        c.open(file)
        print(c.hover(file, line=10, col=5))
"""

import hashlib
import os
import shutil
import time
from pathlib import Path

from lsp import LspClient, DEFAULT_TIMEOUT

# ── Config ────────────────────────────────────────────────────────────────────

JDTLS_LAUNCHER = os.environ.get("JDTLS_LAUNCHER") or shutil.which("jdtls") or "/opt/jdtls/bin/jdtls"
# jdtls 1.51's bundled ASM rejects class version 70 (JDK 26); Corretto 25 is the highest it runs on.
JDTLS_JAVA_HOME = os.environ.get("JDTLS_JAVA_HOME", "/opt/amazon-corretto-25.0.0.36.2-linux-x64")
# How long to wait for the initial project import (first run downloads/builds; later runs are cached).
JDTLS_IMPORT_TIMEOUT = int(os.environ.get("JDTLS_IMPORT_TIMEOUT", "300"))


def _data_dir(root: Path) -> Path:
    """Stable per-workspace -data directory so repeat runs reuse the cached import.

    Keyed by the absolute workspace path so distinct projects never share an index."""
    digest = hashlib.sha1(str(root).encode()).hexdigest()[:12]
    data = Path.home() / ".cache/jdtls-diff" / f"{root.name}-{digest}"
    data.mkdir(parents=True, exist_ok=True)
    return data


# ── JdtlsClient ───────────────────────────────────────────────────────────────

class JdtlsClient(LspClient):
    """jdtls preset: Corretto-25 launch, Eclipse initializationOptions, import-readiness wait."""

    @classmethod
    def start(cls, workspace_root: str | Path,
              import_timeout: int = JDTLS_IMPORT_TIMEOUT) -> "JdtlsClient":
        root = Path(workspace_root).resolve()
        # Disable Lathe's model injection for jdtls's embedded Maven only (m2e runs in-process in
        # the jdtls JVM, so -D reaches it) -- this keeps the imported build path plain-javac even if
        # the project has no .mvn/maven.config, without touching the user's CLI builds.
        cmd = [JDTLS_LAUNCHER, "--jvm-arg=-Dlathe.disabled=true", "-data", str(_data_dir(root))]
        env = {**os.environ, "JAVA_HOME": JDTLS_JAVA_HOME,
               "PATH": f"{JDTLS_JAVA_HOME}/bin{os.pathsep}{os.environ.get('PATH', '')}"}
        client = cls._spawn(cmd, env, root, cwd=root)
        client.wait_until_ready(timeout=import_timeout)
        return client

    def _handshake(self, root: Path):
        resp = self.request("initialize", {
            "processId": os.getpid(),
            "rootUri": root.as_uri(),
            "workspaceFolders": [{"uri": root.as_uri(), "name": root.name}],
            "capabilities": {
                "textDocument": {
                    "publishDiagnostics": {"relatedInformation": False},
                    "hover": {"contentFormat": ["markdown", "plaintext"]},
                    "definition": {"linkSupport": True},
                    "declaration": {"linkSupport": True},
                    "implementation": {"linkSupport": True},
                    "references": {},
                    "documentHighlight": {},
                    "typeHierarchy": {},
                    "callHierarchy": {},
                    "foldingRange": {},
                    "codeAction": {},
                    "rename": {"prepareSupport": True},
                    "documentSymbol": {"hierarchicalDocumentSymbolSupport": True},
                    "completion": {"completionItem": {
                        "snippetSupport": False,
                        "resolveSupport": {"properties": ["detail", "documentation"]},
                    }},
                    "signatureHelp": {},
                },
                "window": {"workDoneProgress": True},
            },
            "initializationOptions": {
                # Let m2e drive the import; mirrors what nvim-jdtls/vscode-java send.
                "settings": {"java": {
                    "import": {"maven": {"enabled": True}},
                    "autobuild": {"enabled": True},
                    "maxConcurrentBuilds": 1,
                }},
                "extendedClientCapabilities": {"progressReportProvider": True},
            },
        }, timeout=max(DEFAULT_TIMEOUT, 60))
        self.notify("initialized", {})
        return resp

    def wait_until_ready(self, timeout: int = JDTLS_IMPORT_TIMEOUT):
        """Block until jdtls finishes importing the project.

        jdtls emits `language/status` {type:"Started", message:"Ready"} once the service is up and
        the initial project build has settled. We also accept the legacy "ServiceReady"."""
        def is_ready(msg: dict) -> bool:
            params = msg.get("params", {})
            return params.get("type") in ("Started", "ServiceReady")

        self.wait_notification("language/status", predicate=is_ready, timeout=timeout)

    def open(self, file, timeout: int = DEFAULT_TIMEOUT, diag_wait: int = 8) -> list[dict]:
        """Open a file and best-effort-collect diagnostics.

        Unlike Lathe, jdtls does not reliably publish a diagnostics notification for a clean file,
        so we return [] on timeout instead of raising -- an empty list means "no problems", which is
        the correct canonical form for the diagnostics comparator."""
        p = Path(file).resolve()
        uri = p.as_uri()
        self._notification_queue("textDocument/publishDiagnostics")
        self.notify("textDocument/didOpen", {
            "textDocument": {"uri": uri, "languageId": "java", "version": 1, "text": p.read_text()},
        })
        try:
            msg = self.wait_notification(
                "textDocument/publishDiagnostics",
                predicate=lambda m: m["params"]["uri"] == uri,
                timeout=diag_wait,
            )
            return msg["params"]["diagnostics"]
        except TimeoutError:
            return []

    def resolve_completion(self, item: dict, timeout: int = DEFAULT_TIMEOUT) -> dict:
        """jdtls returns lazy completion items; resolve fills in detail/documentation."""
        return self.request("completionItem/resolve", item, timeout=timeout)
