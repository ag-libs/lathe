package io.github.aglibs.lathe.install;

import io.github.aglibs.lathe.core.JavaArgfile;
import io.github.aglibs.lathe.core.LatheLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// .lathe/jvm.args: the module-access flags the server JVM needs so the build's classpath javac
// plugins (Error Prone, NullAway, ...) also run in-process. The build tool collects candidate
// tokens from wherever it grants them; only --add-exports / --add-opens are kept. The launcher
// passes the file to java as an @argfile.
public final class JvmArgsWriter {

  private static final Logger LOG = LoggerFactory.getLogger(JvmArgsWriter.class);
  // Only module access: other forked-javac options (-J-Xmx256m) size a short-lived compiler and
  // would starve the long-running server.
  private static final Set<String> ACCESS_FLAGS = Set.of("--add-exports", "--add-opens");

  private JvmArgsWriter() {}

  public static void write(final Path workspaceRoot, final List<String> tokens) {
    final List<String> flags = List.copyOf(new LinkedHashSet<>(accessFlags(tokens)));
    final var latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    final var argsPath = latheDir.resolve(LatheLayout.JVM_ARGS_FILE);
    try {
      if (flags.isEmpty()) {
        Files.deleteIfExists(argsPath);
        return;
      }

      if (JavaArgfile.write(latheDir, argsPath, flags, JavaArgfile.NATIVE_CHARSET)) {
        LOG.info("[sync] jvm.args %d module-access flag(s)".formatted(flags.size()));
      }
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to write jvm.args", e);
    }
  }

  // Both the joined (--add-exports=x) and the two-token (--add-exports x) form, as joined flags.
  public static List<String> accessFlags(final List<String> tokens) {
    final var flags = new ArrayList<String>();
    for (int i = 0; i < tokens.size(); i++) {
      final String token = tokens.get(i);
      if (ACCESS_FLAGS.contains(token) && i + 1 < tokens.size()) {
        i++;
        flags.add("%s=%s".formatted(token, tokens.get(i)));
        continue;
      }

      if (ACCESS_FLAGS.stream().anyMatch(flag -> token.startsWith(flag.concat("=")))) {
        flags.add(token);
      }
    }

    return List.copyOf(flags);
  }
}
