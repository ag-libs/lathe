# Lathe in Emacs (Eglot)

Emacs 29 and later include Eglot, the client you can use to edit Java with Lathe.
Use Xref on a method name to visit its definition or find its references.
This setup works on Linux and macOS. [Windows editor options](../../../README.md#editors)

![Lathe plugged into vanilla Emacs via Eglot — hover, cross-module nav, extract, rename, live diagnostics](../../emacs-tour-ebbbb6f4.gif)

## Setup

Run the [first build](../../../README.md#first-build) in your project.
Eglot starts the launcher relative to its project root, usually the version-control root.
That directory must contain the build's `.lathe/` folder.

Add the following to `init.el` so the registration runs after Eglot loads.

```elisp
(with-eval-after-load 'eglot
  (add-to-list 'eglot-server-programs
               '(java-mode ".lathe/lathe-launcher.sh")))
```

Open a Java file in `java-mode` and run `M-x eglot`.
To connect whenever you open a Java file, add `(add-hook 'java-mode-hook #'eglot-ensure)` to your init file.

## What works

Eglot uses Xref for navigation, Flymake for diagnostics, and ElDoc for documentation.

| Feature                          | Command                                             |
|----------------------------------|-----------------------------------------------------|
| Go to definition (cross-module)  | `M-.`                                               |
| Find references                  | `M-?`                                               |
| Workspace symbols (project-wide) | `M-x xref-find-apropos`                             |
| Completion                       | `C-M-i`                                             |
| Hover / javadoc                  | ElDoc (echo area) · `M-x eldoc-doc-buffer`          |
| Rename (project-wide)            | `M-x eglot-rename`                                  |
| Extract variable / method / refactors | `M-x eglot-code-action-extract` · `eglot-code-actions` |
| Live diagnostics                 | Flymake · `M-x flymake-show-buffer-diagnostics`     |
| Formatting                       | `M-x eglot-format-buffer`                           |

## Not available via plain Eglot

Run/test/debug and scaffolding (`:LatheNew`) are **[Neovim-client](neovim.md)**
features — they use custom client commands, not the standard LSP surface. Call/type-hierarchy and
semantic-token highlighting depend on a newer Eglot than some Emacs builds ship.
