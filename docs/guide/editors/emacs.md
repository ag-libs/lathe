# Lathe in Emacs (Eglot)

Lathe is a standard LSP server, and Emacs ships a standard LSP client — **Eglot** (built in since
Emacs 29). So you get Lathe's build-derived intelligence in a **vanilla `emacs -Q`**, with no plugins
(no `lsp-mode`, no `company`/`corfu`) — just one line of Elisp.

![Lathe plugged into vanilla Emacs via Eglot — hover, cross-module nav, extract, rename, live diagnostics](../../emacs-tour.gif)

## Setup

One line in `init.el` points Eglot at Lathe's launcher for Java files:

```elisp
(add-to-list 'eglot-server-programs
             '(java-mode "~/.cache/lathe/current/lathe-launcher.sh"))
```

Open a Java file in a Maven project Lathe has synced (any `mvn` build populates `.lathe/`), then
`M-x eglot` — or add `eglot-ensure` to `java-mode-hook` to connect automatically. Eglot picks the
project's version-control root, which must be the reactor root where `.lathe/` lives.

## What works

All through Emacs's built-in facilities (Xref, Flymake, ElDoc, `completion-at-point`), resolved from
your real Maven build:

| Feature                          | Command                                             |
|----------------------------------|-----------------------------------------------------|
| Go to definition (cross-module)  | `M-.`                                               |
| Find references                  | `M-?`                                               |
| Workspace symbols (reactor-wide) | `M-x xref-find-apropos`                             |
| Completion                       | `C-M-i`                                             |
| Hover / javadoc                  | ElDoc (echo area) · `M-x eldoc-doc-buffer`          |
| Rename (reactor-wide)            | `M-x eglot-rename`                                  |
| Extract variable / refactors     | `M-x eglot-code-action-extract` · `eglot-code-actions` |
| Live diagnostics                 | Flymake · `M-x flymake-show-buffer-diagnostics`     |

## Not available via plain Eglot

Run/test/debug, scaffolding (`:LatheNew`), and format-on-save are **[Neovim-client](neovim.md)**
features — they use custom client commands, not the standard LSP surface. Call/type-hierarchy and
semantic-token highlighting depend on a newer Eglot than some Emacs builds ship.
