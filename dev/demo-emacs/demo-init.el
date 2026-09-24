;;; demo-init.el --- recording setup for the Lathe Emacs demo -*- lexical-binding: t; -*-
;; Loaded by the dev/demo-emacs/*.tape files via:
;;   emacs -nw -q -l /abs/path/dev/demo-emacs/demo-init.el <file>
;;
;; Wraps examples/emacs/init.el for a clean recording: an isolated package dir
;; (pre-populated by prepare.sh; LATHE_SKIP_PKG_INSTALL keeps the tape offline),
;; no backup/auto-save/lock files touching the working tree, and a high-contrast
;; built-in theme. The repo root is derived from THIS file's location, so the
;; shell may cd anywhere (e.g. into the fixture module) before launching Emacs.

(defvar lathe-demo-repo
  (expand-file-name "../../"
                    (file-name-directory (or load-file-name buffer-file-name default-directory)))
  "Absolute path to the Lathe checkout root.")

(let ((elpa (or (getenv "LATHE_DEMO_ELPA")
                (expand-file-name "dev/demo-emacs/emacsd/elpa/" lathe-demo-repo))))
  (setq user-emacs-directory (file-name-directory (directory-file-name elpa))
        package-user-dir elpa))

(setq inhibit-startup-screen t
      initial-scratch-message nil
      make-backup-files nil
      auto-save-default nil
      create-lockfiles nil
      ring-bell-function 'ignore
      use-dialog-box nil
      eglot-sync-connect t)          ; block until ready so the first beat is live

(load (expand-file-name "examples/emacs/init.el" lathe-demo-repo))

(ignore-errors (load-theme 'modus-vivendi t))
(when (fboundp 'global-display-line-numbers-mode) (global-display-line-numbers-mode 1))
(setq-default display-line-numbers-width 3)
