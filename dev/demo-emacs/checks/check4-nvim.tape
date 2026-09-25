# Level 4 — plain Neovim (different editor). Isolates whether a failure is Emacs-specific or general.
Output "docs/videos/check4-nvim.mp4"
Require nvim
Set Shell "bash"
Set Width 1000
Set Height 600
Set FontSize 22
Type "nvim"
Enter
Sleep 2s
Type "iHello from Neovim"
Escape
Sleep 2s
Type ":q!"
Enter
Sleep 500ms
