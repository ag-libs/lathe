;;; init.el --- Sample Emacs config for the Lathe Java language server  -*- lexical-binding: t; -*-

;; A single-file, self-contained setup showing how Lathe fits into Emacs through
;; the built-in Eglot LSP client: completion, cross-module go-to-definition,
;; diagnostics, and -- the interesting part -- navigation that follows through
;; into dependency and JDK sources. It is a DEMONSTRATION you can read and borrow
;; from, not a config meant to replace your own ~/.emacs.d wholesale.
;;
;; The file has two halves:
;;   1. THE LATHE CORE (Eglot wiring) -- stock Emacs, zero external packages.
;;      Delete part 2 and this still gives you full code intelligence.
;;   2. AN IDE-LIKE UX layer -- a handful of popular packages, auto-installed on
;;      first launch (like the Neovim demo bootstraps lazy.nvim), that bring the
;;      as-you-type completion popup and fuzzy pickers you'd expect from an IDE.
;;
;; Requires Emacs 29+ (Eglot is bundled; verified on 30.1). Lathe must be
;; installed: its launcher is written to ~/.cache/lathe/current/lathe-launcher.sh
;; by `mvn process-test-classes` when the Lathe Maven plugin is on the build.
;;
;; Try it without touching your own config (part 2 installs packages the first
;; time, so this first run needs network):
;;   emacs -q -l /path/to/lathe/examples/emacs/init.el /path/to/project/src/.../Foo.java
;;
;; Environment overrides (all optional), matching the Neovim client:
;;   LATHE_CACHE          cache location            (default: ~/.cache/lathe)
;;   LATHE_SERVER_DIR     run a working-tree server (default: <cache>/current)
;;   LATHE_DEBUG=1        FINE-level server logging (read by the server itself)
;;   LATHE_SKIP_PKG_INSTALL=1  skip the part-2 auto-install (stock Emacs only)

;;; Code:

(require 'cl-lib)
(require 'project)
(require 'eglot)

;;;; --------------------------------------------------------------------------
;;;; Locating the server
;;;; --------------------------------------------------------------------------

(defconst lathe-root-marker ".lathe"
  "Directory name identifying a Lathe workspace root.
`mvn process-test-classes' writes it at the reactor root.")

(defun lathe-cache-root ()
  "Root of the Lathe cache as a directory name.
Honors the LATHE_CACHE environment variable, else ~/.cache/lathe."
  (file-name-as-directory
   (expand-file-name (or (getenv "LATHE_CACHE") "~/.cache/lathe"))))

(defun lathe-launcher-path ()
  "Absolute path to the server launcher Eglot execs.
Honors the LATHE_SERVER_DIR dev override (a built server version directory) so a
working-tree server runs without repointing the shared `current' symlink; falls
back to the installed `current' server otherwise.  Read lazily at connect time,
so the environment need not be set before this file loads."
  (let ((dir (or (getenv "LATHE_SERVER_DIR")
                 (expand-file-name "current" (lathe-cache-root)))))
    (expand-file-name "lathe-launcher.sh" dir)))

;;;; --------------------------------------------------------------------------
;;;; Workspace root detection
;;;; --------------------------------------------------------------------------
;;
;; Two jobs, both done by a single `project.el' backend:
;;
;;   1. For your own sources, walk up to the `.lathe' marker.  This must win over
;;      the built-in Git backend: in a multi-module reactor the nearest `pom.xml'
;;      (and often the Git root) is a submodule, but Lathe's server expects the
;;      REACTOR root as its workspace -- it reads the root verbatim and does not
;;      re-derive it.
;;
;;   2. For dependency and JDK sources, there is no marker to find: they live
;;      under the Lathe cache, outside any project.  We fall back to the last
;;      resolved reactor root so those buffers resolve to the SAME project object
;;      -- which is what makes Eglot attach them to the already-running server
;;      instead of leaving them unmanaged.  This mirrors the Neovim client's
;;      `M.last_root' fallback.

(defvar lathe--last-root nil
  "Most recently resolved workspace root; the fallback for cache-only buffers.")

(defvar lathe--projects (make-hash-table :test 'equal)
  "Interned project objects keyed by root.
`project-current' must return the SAME object for a given root across calls,
because Eglot keys its server table by project identity (`eql').")

(defun lathe--project-for (root)
  "Return the interned Lathe project for ROOT, remembering it as the last root."
  (let ((root (file-name-as-directory (expand-file-name root))))
    (setq lathe--last-root root)
    (or (gethash root lathe--projects)
        (puthash root (list 'lathe root) lathe--projects))))

(defun lathe--find-project (dir)
  "Locate the Lathe project owning DIR, or nil.
Walks up to `lathe-root-marker'; for files under the Lathe cache (dependency
and JDK sources) falls back to the last resolved reactor root."
  (let ((marked (locate-dominating-file dir lathe-root-marker)))
    (cond
     (marked (lathe--project-for marked))
     ((and lathe--last-root
           (string-prefix-p (lathe-cache-root) (expand-file-name dir)))
      (lathe--project-for lathe--last-root))
     (t nil))))

(cl-defmethod project-root ((project (head lathe)))
  (nth 1 project))

;; Prepend, so `.lathe' takes precedence over the built-in VC (Git) backend.
(add-hook 'project-find-functions #'lathe--find-project)

;;;; --------------------------------------------------------------------------
;;;; Eglot server registration
;;;; --------------------------------------------------------------------------

(defclass lathe-eglot-server (eglot-lsp-server) ()
  "Eglot server subclass for Lathe, used only to carry initializationOptions.")

(cl-defmethod eglot-initialization-options ((_server lathe-eglot-server))
  "Opt in to Lathe's on-demand Google Java Format.
The server advertises `textDocument/formatting' only when this is sent; drop
this method to leave formatting off."
  '(:lathe (:formatter "google")))

;; One entry covers both the classic and the tree-sitter Java modes, grouped so a
;; dependency buffer opened in either can attach to the same server.  The contact
;; is a function so LATHE_SERVER_DIR is honored at connect time.
(add-to-list 'eglot-server-programs
             (cons '(java-mode java-ts-mode)
                   (lambda (&rest _)
                     (list 'lathe-eglot-server (lathe-launcher-path)))))

;; Follow definitions into dependency/JDK sources reached via xref even though
;; they sit outside the project tree.  Belt-and-suspenders with the cache-root
;; fallback above, and covers files opened by means other than a mode hook.
(setq eglot-extend-to-xref t)

;; Auto-start (or attach to) the server when a Java buffer opens -- including the
;; dependency buffers you jump into.
(dolist (hook '(java-mode-hook java-ts-mode-hook))
  (add-hook hook #'eglot-ensure))

;;;; --------------------------------------------------------------------------
;;;; Core editing defaults (stock Emacs)
;;;; --------------------------------------------------------------------------

;; TAB completes at point (Eglot supplies the candidates) and otherwise indents.
;; With Corfu below, completion also pops up automatically as you type.
(setq tab-always-indent 'complete)

;; `M-.' jumps to the symbol under point without prompting.  (Eglot supplies a
;; dummy "identifier at point", so the only time you'd otherwise see a prompt is
;; when point is not on a symbol.)
(setq xref-prompt-for-identifier nil)

;; Signature/docs of the symbol at point, in the echo area; inlay hints off to
;; keep the demo uncluttered.  The composed strategy shows the hover doc AND the
;; diagnostic under point together instead of one clobbering the other.
(setq eldoc-echo-area-use-multiline-p t)
(add-hook 'eglot-managed-mode-hook
          (lambda ()
            (when (fboundp 'eglot-inlay-hints-mode) (eglot-inlay-hints-mode -1))
            (setq-local eldoc-documentation-strategy #'eldoc-documentation-compose-eagerly)))

;; Show each diagnostic inline at the end of its line (Emacs 30, Error-Lens
;; style) -- works in the terminal, no package required.
(when (boundp 'flymake-show-diagnostics-at-end-of-line)
  (setq flymake-show-diagnostics-at-end-of-line t))

;; Server hygiene: shut the server down with its last buffer and keep the
;; JSON-RPC events buffer from growing unbounded.
(setq eglot-autoshutdown t)
(when (boundp 'eglot-events-buffer-config)
  (setq eglot-events-buffer-config '(:size 0 :format short)))

;;;; --------------------------------------------------------------------------
;;;; Collapse the import block -- the vim client's LSP-driven import fold
;;;; --------------------------------------------------------------------------
;; Eglot ships no `textDocument/foldingRange' client, so we ask Lathe directly
;; (it tags the import block with kind "imports") and collapse that region into a
;; one-line summary with an overlay -- the same idea as the Neovim client's fold.

(defvar lathe-fold-imports-on-open t
  "When non-nil, auto-collapse the import block after a Java buffer is analyzed.")

(defvar-local lathe--imports-overlay nil)

(defun lathe--imports-range ()
  "The server's folding range for the import block, or nil."
  (when (eglot-current-server)
    (seq-find (lambda (r) (equal (plist-get r :kind) "imports"))
              (eglot--request (eglot-current-server) :textDocument/foldingRange
                              (list :textDocument (eglot--TextDocumentIdentifier))))))

(defun lathe--line-start (line)
  "Buffer position at the start of 0-based LSP LINE."
  (save-excursion (goto-char (point-min)) (forward-line line) (line-beginning-position)))

(defun lathe-fold-imports (&optional range)
  "Collapse the import block into a single summary line.
RANGE, if given, is a server folding range already known to be the imports one."
  (interactive)
  (lathe-unfold-imports)
  (let ((r (or range (lathe--imports-range) (user-error "No import fold available"))))
    (let* ((beg (lathe--line-start (plist-get r :startLine)))
           (end (save-excursion (goto-char (lathe--line-start (plist-get r :endLine)))
                                (line-end-position)))
           (ov (make-overlay beg end)))
      (overlay-put ov 'invisible t)
      (overlay-put ov 'display (format "import … (%d lines) " (count-lines beg end)))
      (overlay-put ov 'face 'shadow)
      (overlay-put ov 'isearch-open-invisible #'delete-overlay)
      (setq lathe--imports-overlay ov))))

(defun lathe-unfold-imports ()
  "Expand a previously collapsed import block."
  (interactive)
  (when (overlayp lathe--imports-overlay) (delete-overlay lathe--imports-overlay))
  (setq lathe--imports-overlay nil))

(defun lathe-toggle-imports-fold ()
  "Toggle the import block fold."
  (interactive)
  (if (and (overlayp lathe--imports-overlay) (overlay-buffer lathe--imports-overlay))
      (lathe-unfold-imports)
    (lathe-fold-imports)))

(defun lathe--auto-fold-imports (buf tries)
  "Fold imports in BUF once the server can answer, retrying up to TRIES times.
The folding range is only available after Lathe has analyzed the file, which
lands slightly after the buffer is managed -- so we poll rather than fire once."
  (when (and (buffer-live-p buf) (> tries 0))
    (with-current-buffer buf
      (let ((ranges (and (eglot-current-server)
                         (ignore-errors
                           (eglot--request
                            (eglot-current-server) :textDocument/foldingRange
                            (list :textDocument (eglot--TextDocumentIdentifier)))))))
        (cond
         ((seq-find (lambda (r) (equal (plist-get r :kind) "imports")) ranges)
          (ignore-errors
            (lathe-fold-imports
             (seq-find (lambda (r) (equal (plist-get r :kind) "imports")) ranges))))
         (ranges nil) ; server answered but this file has no import block -- stop
         (t (run-with-timer 0.6 nil #'lathe--auto-fold-imports buf (1- tries))))))))

;; Auto-fold once Lathe has analyzed the file (default on, no startup flag
;; needed); guarded so a miss never disrupts editing.
(add-hook 'eglot-managed-mode-hook
          (lambda ()
            (when lathe-fold-imports-on-open
              (lathe--auto-fold-imports (current-buffer) 25))))

(with-eval-after-load 'eglot
  (keymap-set eglot-mode-map "C-c l TAB" #'lathe-toggle-imports-fold))

;; ==========================================================================
;; PART 2 -- IDE-LIKE UX LAYER  (delete everything below for a stock setup)
;; ==========================================================================
;; A small, popular package set that turns Eglot's built-ins into the
;; as-you-type, fuzzy-picker experience you'd expect from an IDE -- the Emacs
;; counterpart to the Neovim demo's blink.cmp + Telescope + which-key.  Packages
;; auto-install on first launch; set LATHE_SKIP_PKG_INSTALL=1 to skip and run
;; stock.  Every use is guarded, so if a package is missing the core above still
;; works.

(require 'package)
(setq package-archives
      '(("gnu"    . "https://elpa.gnu.org/packages/")
        ("nongnu" . "https://elpa.nongnu.org/nongnu/")
        ("melpa"  . "https://melpa.org/packages/")))
(package-initialize)   ; always, so already-installed packages load...
(unless (getenv "LATHE_SKIP_PKG_INSTALL")  ; ...but only auto-install when allowed
  (let ((want '(compat                              ; shared dep -- install first
                corfu corfu-terminal cape           ; completion popup
                vertico orderless marginalia         ; minibuffer / fuzzy
                consult consult-eglot embark embark-consult
                eldoc-box)))                         ; childframe hover (GUI)
    (when (seq-some (lambda (p) (not (package-installed-p p))) want)
      (ignore-errors (package-refresh-contents)))
    (dolist (p want)
      (unless (package-installed-p p) (ignore-errors (package-install p))))))

;; --- Corfu: as-you-type completion popup (like blink.cmp) -----------------
(when (require 'corfu nil t)
  (setq corfu-auto t              ; pop up automatically...
        corfu-auto-delay 0.1
        corfu-auto-prefix 2       ; ...after 2 chars
        corfu-cycle t)
  (global-corfu-mode)
  (when (fboundp 'corfu-popupinfo-mode) (corfu-popupinfo-mode)) ; doc beside candidates
  ;; Terminal Emacs has no child frames; corfu-terminal draws the popup in a TTY.
  (unless (display-graphic-p)
    (when (require 'corfu-terminal nil t) (corfu-terminal-mode 1)))
  ;; IntelliJ / blink.cmp feel: TAB cycles the menu, RET accepts.
  (keymap-set corfu-map "TAB" #'corfu-next)
  (keymap-set corfu-map "S-TAB" #'corfu-previous)
  (keymap-set corfu-map "RET" #'corfu-insert))

;; --- Cape: extra completion sources (paths, words in buffer) --------------
(when (require 'cape nil t)
  (add-hook 'completion-at-point-functions #'cape-file)
  (add-hook 'completion-at-point-functions #'cape-dabbrev t))

;; --- Vertico + Orderless + Marginalia: fuzzy minibuffer (like Telescope) --
(when (require 'vertico nil t) (vertico-mode))
(when (require 'marginalia nil t) (marginalia-mode))
(when (require 'orderless nil t)
  (setq completion-styles '(orderless basic)
        completion-category-defaults nil
        completion-category-overrides '((file (styles partial-completion))
                                        (eglot (styles orderless))
                                        (eglot-capf (styles orderless)))))

;; --- Consult: previewable pickers; route xref results through it -----------
(when (require 'consult nil t)
  (setq xref-show-xrefs-function #'consult-xref
        xref-show-definitions-function #'consult-xref))

;; --- Embark: act on the thing at point (a context menu) --------------------
(when (require 'embark nil t)
  (keymap-global-set "C-." #'embark-act)
  (require 'embark-consult nil t))

;; --- eldoc-box: hover docs in a childframe at point (GUI only; the terminal
;;     keeps the echo-area eldoc from the core above) -------------------------
(when (and (display-graphic-p) (require 'eldoc-box nil t))
  (add-hook 'eglot-managed-mode-hook #'eldoc-box-hover-at-point-mode))

;; --- which-key: live keybinding hints (built into Emacs 30) ---------------
(when (fboundp 'which-key-mode) (which-key-mode 1))

;;;; --------------------------------------------------------------------------
;;;; Keybindings -- an LSP group under `C-c l' (discoverable via which-key)
;;;; --------------------------------------------------------------------------
;; Built-ins stay on their usual keys: M-. definition, M-, back, M-? references
;; (all previewed through Consult), M-n / M-p between diagnostics.

(with-eval-after-load 'flymake
  (keymap-set flymake-mode-map "M-n" #'flymake-goto-next-error)
  (keymap-set flymake-mode-map "M-p" #'flymake-goto-prev-error))

(with-eval-after-load 'eglot
  (keymap-set eglot-mode-map "C-c l a" #'eglot-code-actions)
  (keymap-set eglot-mode-map "C-c l r" #'eglot-rename)
  (keymap-set eglot-mode-map "C-c l f" #'eglot-format-buffer)
  (keymap-set eglot-mode-map "C-c l h" #'eldoc)                    ; hover -> echo area
  (keymap-set eglot-mode-map "C-c l m" #'eglot-find-implementation)
  (keymap-set eglot-mode-map "C-c l t" #'eglot-find-typeDefinition)
  (when (fboundp 'consult-eglot-symbols)
    (keymap-set eglot-mode-map "C-c l s" #'consult-eglot-symbols)) ; workspace symbols
  (when (fboundp 'consult-imenu)
    (keymap-set eglot-mode-map "C-c l o" #'consult-imenu))         ; symbols in file
  (when (fboundp 'consult-flymake)
    (keymap-set eglot-mode-map "C-c l d" #'consult-flymake)))      ; list diagnostics

(provide 'init)
;;; init.el ends here
