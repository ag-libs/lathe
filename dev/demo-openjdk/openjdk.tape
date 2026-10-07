# Lathe for OpenJDK — single hero clip. Run from the repo root after ./dev/demo-openjdk/prepare.sh:
#   ./dev/demo-openjdk/record.sh      ->   docs/openjdk-demo-<hash>.gif
#
# One calm thread on the REAL OpenJDK source, built on the JDK's own javac. We start from an EMPTY,
# lathe-initialized editor in the JDK folder, find a class by searching the whole JDK, then edit it:
#   Beat 1  workspace symbol search across the whole JDK  -> open java.net.http/HttpHeaders.java
#   Beat 2  add a method, with completion from the build's own classpath
#   Beat 3  break it — live, javac-accurate diagnostics (then undo)
#   Beat 4  cross-module go-to-definition (java.net.http -> java.base) — the finale
#
# Uses an isolated COPY of the sample config examples/nvim (dev/demo-openjdk/.nvim), the Lathe
# client+server linked at $LATHE_OPENJDK_DIR/.lathe (installed by lathe-openjdk-maven-plugin:sync), and an
# already-built + synced OpenJDK checkout at $LATHE_OPENJDK_DIR (see docs/guide/openjdk.md).
#
# Leader is '\' (examples/nvim), so workspace-symbol search is '\ws'. A blank `nvim` has no `java`
# filetype to auto-start Lathe, so the hidden setup runs `:LatheStart` to initialize it on the JDK
# workspace (root = cwd's .lathe) and warm the index before recording. blink.cmp: Enter accepts the
# completion menu (preset none). Sleeps are tuned against the recorded clip — re-tune if the JDK,
# server, or host timing changes.

Output "docs/videos/openjdk-demo.mp4"
Require nvim

Set Shell "bash"
# Monaco for the code, Nerd-Font fallback for gutter/statusline glyphs. 1.5x scale keeps Monaco crisp
# after GIF palette quantization. Matches the Maven hero demo (dev/demo/demo.tape).
Set FontFamily "Monaco, Cascadia Mono NF"
Set FontSize 24
Set Width 1800
Set Height 1080
Set Padding 30
Set TypingSpeed 55ms

# --- hidden setup: isolated config + checkout client + installed server; open an EMPTY editor in the
# JDK folder, initialize Lathe with :LatheStart, and warm the index so the first search is instant. ---
Hide
Type 'R="$PWD"' Enter
Type 'source "$R/dev/demo-openjdk/env.sh"' Enter
Type 'cd "$LATHE_OPENJDK_DIR" || exit 1' Enter
Type 'clear' Enter
Type 'nvim -n' Enter          # empty editor, no file; -n disables swap (VHS kills nvim with no clean :q,
                              # so without this a leftover swap triggers an E325 recovery prompt on reopen)
Sleep 1500ms
Type ':LatheStart' Enter      # initialize Lathe on the JDK workspace (root = cwd's .lathe)
Sleep 25s                     # server start + JDK workspace index warm (symbols / def / completion ready)
Ctrl+L                        # redraw: clear the "workspace ready" message from the command line
Show

Sleep 1800ms                  # dwell on the clean, empty, Lathe-initialized editor

# --- Beat 1: find a class across the whole JDK, then open it ---
Type '\ws'                    # <leader>ws = dynamic workspace symbols (leader = '\')
Sleep 1200ms
Type 'HttpHeaders'
Sleep 2200ms                  # server-ranked results across the JDK; the public type ranks first
Enter                         # open java.net.http/share/classes/java/net/http/HttpHeaders.java
Sleep 3000ms                  # dwell on the opened source (cursor on the class declaration)

# --- Beat 2: add a method, with completion from the build's classpath ---
Type 'o'                      # open a line inside the class body
Type 'public int count() { return map().'
Sleep 1700ms                  # member completion for Map (java.base) — size(), get(), isEmpty(), ...
Type 'size'
Sleep 1000ms                  # the menu narrows to size()
Enter                         # accept -> map().size()
Escape
Type 'A'                      # append at end of line...
Type '; }'                    # ...close the statement + body: `public int count() { return map().size(); }`
Escape
Sleep 3200ms                  # valid — count() returns int, no diagnostics

# --- Beat 3: break it — live, javac-accurate diagnostics (then undo) ---
Type '_'                      # first non-blank on the method line
Type 'f{'                     # jump to the opening brace
Type 'a'                      # insert just after `{`
Type ' int bad = map();'      # a Map assigned to int — a type error inside the method
Escape
Sleep 3600ms                  # live diagnostic: "incompatible types: Map<...> cannot be converted to int"
Type 'u'                      # undo the broken statement — leave the method valid
Sleep 1800ms

# --- Beat 4: cross-module go-to-definition (java.net.http -> java.base) — the finale ---
Type '/Map<String' Enter      # the `public Map<String,List<String>> map()` accessor; cursor lands on Map
Sleep 1000ms
Type 'gd'                     # telescope lsp_definitions; a single result auto-jumps into java.base
Sleep 4200ms                  # dwell on java.base/share/classes/java/util/Map.java

Sleep 700ms
