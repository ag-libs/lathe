package io.github.aglibs.lathe.openjdk;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * Parses one module's {@code _the.<module>_batch.cmdline} into the compiled-deps analysis config.
 * Drops the launcher prefix and the build-mechanics / source-model flags, keeps the fidelity flags
 * ({@code -source/-target}, {@code --add-exports}, {@code --add-reads}, {@code -encoding}),
 * synthesises the {@code --patch-module <module>=.} overlay, and surfaces the {@code
 * --module-source-path} patterns for the writer to resolve into source roots. Pure (no filesystem)
 * so it is unit-testable from a checked-in fixture.
 */
final class CmdlineReader {

  private CmdlineReader() {}

  record Parsed(List<String> compilerArgs, String encoding, List<String> sourceRootPatterns) {
    Parsed {
      compilerArgs = List.copyOf(compilerArgs);
      sourceRootPatterns = List.copyOf(sourceRootPatterns);
    }
  }

  static Parsed read(final String cmdline, final String module) {
    final var addFlags = new ArrayList<String>();
    final var sourceRootPatterns = new ArrayList<String>();
    String source = null;
    String target = null;
    String encoding = "UTF-8";

    final var it = stripLauncher(tokenize(cmdline)).iterator();
    while (it.hasNext()) {
      final String token = it.next();
      switch (token) {
        case "-source" -> source = next(it);
        case "-target" -> target = next(it);
        case "-encoding" -> encoding = next(it);
        case "--add-exports", "--add-reads" -> {
          addFlags.add(token);
          addFlags.add(next(it));
        }
        case "--module-source-path" -> sourceRootPatterns.addAll(patterns(next(it), module));
        default -> {
          // Everything else is build mechanics or the source-model flags (--system none, -Werror,
          // -d/-h, -Xplugin, @filelist, ...) — dropped. A two-token flag's value falls through here
          // too and is harmlessly dropped on the next iteration.
        }
      }
    }

    final var args = new ArrayList<String>();
    addPair(args, "-source", source);
    addPair(args, "-target", target);
    args.add("-implicit:none");
    args.addAll(addFlags);
    args.add("--patch-module");
    args.add(module + "=.");
    return new Parsed(args, encoding, sourceRootPatterns);
  }

  private static List<String> tokenize(final String cmdline) {
    return Arrays.stream(cmdline.trim().split("\\s+"))
        .map(CmdlineReader::unquote)
        .filter(token -> !token.isEmpty())
        .toList();
  }

  private static String unquote(final String token) {
    final boolean quoted =
        token.length() >= 2
            && ((token.startsWith("\"") && token.endsWith("\""))
                || (token.startsWith("'") && token.endsWith("'")));
    return quoted ? token.substring(1, token.length() - 1) : token;
  }

  // javac-server mode: `… javacserver.Main --conf=<f> <args>`; interim mode: `… <…>.javac.Main
  // <args>`.
  private static List<String> stripLauncher(final List<String> tokens) {
    for (int i = 0; i < tokens.size(); i++) {
      final String token = tokens.get(i);
      if (token.equals("javacserver.Main")) {
        return tokens.subList(Math.min(i + 2, tokens.size()), tokens.size());
      }
      if (token.endsWith(".javac.Main")) {
        return tokens.subList(i + 1, tokens.size());
      }
    }
    return tokens;
  }

  // "<gensrc>/*:<jdk>/src/*/{os}/classes:…" → the module's roots, substituting it for the '*' glob.
  private static List<String> patterns(final String moduleSourcePath, final String module) {
    return Arrays.stream(moduleSourcePath.split(File.pathSeparator))
        .filter(p -> !p.isEmpty())
        .map(p -> p.replace("*", module))
        .toList();
  }

  private static void addPair(final List<String> args, final String flag, final String value) {
    if (value != null) {
      args.add(flag);
      args.add(value);
    }
  }

  private static String next(final Iterator<String> it) {
    return it.hasNext() ? it.next() : null;
  }
}
