package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.install.SyncException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

// Derives .lathe/jvm.args: the module-access flags the server JVM needs so the build's classpath
// javac plugins (Error Prone, NullAway, ...) also run in-process. A build grants them either to a
// forked javac (-J flags in compilerArgs) or to Maven's own JVM (.mvn/jvm.config); the launcher
// passes the file to java as an @argfile. Read from the model, not the captured lsp-params files:
// sync runs in the reactor root, before any child module compiles.
final class JvmArgsWriter {

  private static final String COMPILER_PLUGIN_KEY =
      Plugin.constructKey(
          LatheLayout.MAVEN_COMPILER_PLUGIN_GROUP_ID,
          LatheLayout.MAVEN_COMPILER_PLUGIN_ARTIFACT_ID);
  private static final String FORKED_JVM_ARG_PREFIX = "-J";

  // Only module access is carried over: other forked-javac options (e.g. -J-Xmx256m) size a
  // short-lived compiler and would starve the long-running server.
  private static final Set<String> ACCESS_FLAGS = Set.of("--add-exports", "--add-opens");

  private final Log log;

  JvmArgsWriter(final Log log) {
    this.log = log;
  }

  void write(final Path workspaceRoot, final List<MavenProject> projects) {
    final var args = new LinkedHashSet<String>();
    args.addAll(accessFlags(forkedJvmArgs(projects)));
    args.addAll(accessFlags(jvmConfigTokens(workspaceRoot)));
    final var latheDir = workspaceRoot.resolve(LatheLayout.LATHE_DIR);
    final var argsPath = latheDir.resolve(LatheLayout.JVM_ARGS_FILE);
    try {
      if (args.isEmpty()) {
        Files.deleteIfExists(argsPath);
        return;
      }

      final var content = String.join("\n", args).concat("\n");
      Files.createDirectories(latheDir);
      if (Files.exists(argsPath)
          && content.equals(Files.readString(argsPath, StandardCharsets.UTF_8))) {
        return;
      }

      FileUtil.writeAtomically(latheDir, argsPath, content, false);
      log.info("[sync] jvm.args %d module-access flag(s)".formatted(args.size()));
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to write jvm.args", e);
    }
  }

  // Keeps only --add-exports/--add-opens, joining the two-token form into one so the same grant
  // from both sources dedupes.
  static List<String> accessFlags(final List<String> tokens) {
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

  private static List<String> forkedJvmArgs(final List<MavenProject> projects) {
    return projects.stream()
        .map(JvmArgsWriter::compilerPlugin)
        .filter(Objects::nonNull)
        .flatMap(JvmArgsWriter::configurations)
        .flatMap(JvmArgsWriter::compilerArgs)
        .filter(arg -> arg.startsWith(FORKED_JVM_ARG_PREFIX))
        .map(arg -> arg.substring(FORKED_JVM_ARG_PREFIX.length()))
        .toList();
  }

  private static Plugin compilerPlugin(final MavenProject project) {
    return project.getBuild() == null
        ? null
        : project.getBuild().getPluginsAsMap().get(COMPILER_PLUGIN_KEY);
  }

  // Plugin-level and per-execution configuration; overlap is harmless as the result is deduped.
  private static Stream<Xpp3Dom> configurations(final Plugin plugin) {
    return Stream.concat(
            Stream.of(plugin.getConfiguration()),
            plugin.getExecutions().stream().map(PluginExecution::getConfiguration))
        .filter(Objects::nonNull)
        .map(Xpp3Dom.class::cast);
  }

  private static Stream<String> compilerArgs(final Xpp3Dom configuration) {
    final Xpp3Dom compilerArgs = configuration.getChild("compilerArgs");
    return compilerArgs == null
        ? Stream.empty()
        : Arrays.stream(compilerArgs.getChildren())
            .map(Xpp3Dom::getValue)
            .filter(Objects::nonNull)
            .map(String::trim);
  }

  private static List<String> jvmConfigTokens(final Path workspaceRoot) {
    final var jvmConfig =
        workspaceRoot.resolve(LatheLayout.MVN_DIR).resolve(LatheLayout.MVN_JVM_CONFIG_FILE);
    if (!Files.isReadable(jvmConfig)) {
      return List.of();
    }

    try {
      return Files.readAllLines(jvmConfig, StandardCharsets.UTF_8).stream()
          .map(String::trim)
          .filter(line -> !line.startsWith("#"))
          .flatMap(line -> Arrays.stream(line.split("\\s+")))
          .filter(token -> !token.isEmpty())
          .toList();
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to read %s".formatted(jvmConfig), e);
    }
  }
}
