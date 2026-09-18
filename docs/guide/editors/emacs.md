# Lathe in Emacs (Eglot)

How to drive Lathe from Emacs with the built-in **Eglot** client, and a suggested keymap for every
action. For *what* each feature does (editor-agnostic), see the
[feature reference in the README](../../../README.md#features).

Requires **Emacs 29+** (Eglot is bundled; verified on 30.1). Everything here works with **stock Emacs
and zero external packages** — Lathe is a plain stdio LSP server, so Eglot needs only to know how to
launch it. A second, optional layer of popular packages turns Eglot's built-ins into an IDE-like
experience (as-you-type completion popup, fuzzy pickers); see [IDE-like UX](#ide-like-ux).

> For a complete, working example that assembles everything below — the single-file config used to
> record the demo — see [`examples/emacs/init.el`](../../../examples/emacs/init.el). Try it without
> touching your own setup:
>
> ```sh
> emacs -q -l /path/to/lathe/examples/emacs/init.el /path/to/project/src/.../Foo.java
> ```

As with any Maven build, run `mvn process-test-classes` once so `.lathe/` and the launcher exist
before opening a Java file.

## Install (the essential wiring)

Three things: tell Eglot how to launch Lathe, teach `project.el` to find the reactor root, and
auto-start on Java buffers.

```elisp
(require 'eglot)

;; 1. Launch the Lathe server (honors LATHE_SERVER_DIR, else the installed `current`).
(defun lathe-launcher-path ()
  (let ((dir (or (getenv "LATHE_SERVER_DIR")
                 (expand-file-name "current"
                                   (or (getenv "LATHE_CACHE")
                                       (expand-file-name "~/.cache/lathe"))))))
    (expand-file-name "lathe-launcher.sh" dir)))

(add-to-list 'eglot-server-programs
             (cons '(java-mode java-ts-mode)
                   (lambda (&rest _) (list (lathe-launcher-path)))))

;; 2. The server takes the workspace root verbatim, so hand it the REACTOR root
;;    (the `.lathe` marker), not the nearest pom.xml.
(defun lathe--find-project (dir)
  (when-let ((root (locate-dominating-file dir ".lathe")))
    (cons 'lathe root)))
(cl-defmethod project-root ((project (head lathe))) (cdr project))
(add-hook 'project-find-functions #'lathe--find-project)

;; 3. Auto-start on Java buffers.
(dolist (h '(java-mode-hook java-ts-mode-hook)) (add-hook h #'eglot-ensure))
```

The example config additionally memoizes the reactor root so buffers you jump *into* — dependency and
JDK sources under `~/.cache/lathe/` — attach to the **same** running server (see
[Dependency & JDK sources](#dependency--jdk-sources)).

To opt in to Google Java Format, send it as an initialization option:

```elisp
(defclass lathe-eglot-server (eglot-lsp-server) ())
(cl-defmethod eglot-initialization-options ((_ lathe-eglot-server))
  '(:lathe (:formatter "google")))
;; ...and prepend `lathe-eglot-server' to the server-programs contact list.
```

## LSP actions

Lathe implements these endpoints; Eglot wires most to built-in keys via `xref`, `eldoc`, and
`flymake`. Bind or rebind freely. The `C-c l` group below is what the example config sets.

| Action | Emacs command | Default | Suggested |
|---|---|---|---|
| Go to definition (local, dependency, and JDK sources) | `xref-find-definitions` | `M-.` | `M-.` |
| Jump back | `xref-go-back` | `M-,` | `M-,` |
| Find references | `xref-find-references` | `M-?` | `M-?` |
| Go to implementation / subtypes | `eglot-find-implementation` | — | `C-c l m` |
| Go to type definition | `eglot-find-typeDefinition` | — | `C-c l t` |
| Hover (AST-resolved Javadoc) | `eldoc` / `eldoc-doc-buffer` | echo area (auto) · `C-h .` | `C-c l h` |
| Completion (with auto-import) | `completion-at-point` | `C-M-i` | auto (see [Completion](#completion)) |
| Code action | `eglot-code-actions` | — | `C-c l a` |
| Rename | `eglot-rename` | — | `C-c l r` |
| Document symbols (this file) | `consult-imenu` / `imenu` | — | `C-c l o` |
| Workspace symbols (whole reactor) | `consult-eglot-symbols` | — | `C-c l s` |
| Next / previous diagnostic | `flymake-goto-next-error` / `-prev-error` | `M-g n` / `M-g p` | `M-n` / `M-p` |
| List diagnostics | `consult-flymake` / `flymake-show-buffer-diagnostics` | — | `C-c l d` |
| Fold / unfold imports | `lathe-toggle-imports-fold` | auto on open | `C-c l TAB` |

> **`M-.` prompting "Find definitions of …"?** That means point wasn't on a symbol. Set
> `(setq xref-prompt-for-identifier nil)` so it always uses the symbol under point. (Eglot does not set
> this for you.)

## Completion

Out of the box, completion is **manual** (`C-M-i`) and appears in a `*Completions*` buffer — stock
Emacs has no auto-popup. Add [`corfu`](https://github.com/minad/corfu) for the as-you-type popup:

```elisp
(setq corfu-auto t corfu-auto-prefix 2 corfu-auto-delay 0.1 tab-always-indent 'complete)
(global-corfu-mode)
(unless (display-graphic-p)            ; terminal Emacs has no child frames
  (corfu-terminal-mode 1))             ; package: corfu-terminal
```

Candidates come straight from your build's classpath, including dependencies and generated types.

## Dependency & JDK sources

Go-to-definition into a third-party dependency or the JDK **just works**: Lathe returns plain
`file://` URIs to real extracted `.java` files (under `~/.cache/lathe/deps/` and
`~/.cache/lathe/jdks/`) — no decompilation, no `jdt://` scheme. Emacs opens them like any file.

To keep navigating *inside* such a file (hover, further goto), the buffer must attach to the same
server. Two settings handle it — the memoized-root `project.el` backend in the example config, plus:

```elisp
(setq eglot-extend-to-xref t)   ; manage files jumped-to outside the project tree
```

## Imports folding

Lathe reports an `imports`-kind folding range for the import block. Eglot has no folding-range client,
so the example config asks the server directly and collapses the block into a one-line summary — the
same idea as the Neovim client. It **auto-folds once the server has analyzed the file** (default on;
`lathe-fold-imports-on-open`), and `C-c l TAB` toggles it. Commands: `lathe-fold-imports`,
`lathe-unfold-imports`, `lathe-toggle-imports-fold`.

## Formatting

With the `:formatter "google"` init option (above), Lathe advertises document formatting:

| Action | Command | Suggested |
|---|---|---|
| Format buffer | `eglot-format-buffer` | `C-c l f` |
| Format region | `eglot-format` | — |

## IDE-like UX

The example config bootstraps a small, popular package set (auto-installed on first launch) that
brings the experience close to a graphical IDE — the Emacs counterpart to the Neovim demo's
completion menu and fuzzy pickers:

| Concern | Packages |
|---|---|
| As-you-type completion popup | `corfu`, `corfu-terminal` (TTY), `cape` |
| Fuzzy minibuffer / pickers | `vertico`, `orderless`, `marginalia`, `consult` |
| Whole-reactor symbol search | `consult-eglot` (`consult-eglot-symbols`) |
| Act-on-thing menu | `embark`, `embark-consult` |
| Childframe hover (GUI) | `eldoc-box` |
| Keybinding hints | `which-key` (built into Emacs 30) |

Delete that block for a fully stock, zero-dependency setup — the Lathe core above still gives you full
code intelligence.

## Not yet in Emacs

The Neovim client's **run / test / debug** surface (runnables, a neotest adapter, DAP) is not wired
for Emacs. Navigation, completion, diagnostics, refactors, formatting, and folding are all supported.
These run through Lathe's custom `workspace/executeCommand` commands (see
[the Neovim guide](neovim.md#custom-server-commands) for the contract) and could be added to an Emacs
client later.

## Verbose logging

- Server: set `LATHE_DEBUG=1` in the environment for `FINE`-level logs. Lathe logs to stderr, which
  Eglot captures in the hidden `*EGLOT <project> stderr*` buffer.
- JSON-RPC traffic: `M-x eglot-events-buffer`.

## Troubleshooting (Emacs)

| Symptom | Fix |
|---|---|
| `M-.` prompts for an identifier | Point isn't on a symbol; or set `xref-prompt-for-identifier` to `nil`. |
| No auto-completion popup | Stock completion is manual (`C-M-i`); add `corfu` with `corfu-auto`. In a terminal, also `corfu-terminal`. |
| Server not attached | `M-x eglot-current-server`; ensure `.lathe/` exists (`mvn process-test-classes`) and the launcher path is correct. |
| Wrong workspace root (multi-module) | Root must be the reactor (the `.lathe` marker), not a submodule `pom.xml` — use the `project.el` backend above. |
| Can't navigate deeper inside a dependency file | Set `eglot-extend-to-xref t` and use the memoized-root backend from the example config. |
