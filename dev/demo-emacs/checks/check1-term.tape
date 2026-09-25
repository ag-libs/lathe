# Level 1 — bare terminal, no editor. Proves vhs core capture (ttyd + browser + ffmpeg) works at all.
Output "docs/videos/check1-term.mp4"
Require bash
Set Shell "bash"
Set Width 800
Set Height 400
Set FontSize 22
Type "echo hello from vhs"
Enter
Sleep 500ms
Type "date"
Enter
Sleep 1500ms
