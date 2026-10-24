package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.install.JvmArgsWriter;
import io.github.aglibs.lathe.install.SyncException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

// Collects the module-access flags this Maven build grants, for JvmArgsWriter: to a forked javac
// (-J flags in compilerArgs/compilerArgument) or to Maven's own JVM (.mvn/jvm.config, and the
// jdk.compiler grants in MAVEN_OPTS). Read from the model, not the captured lsp-params files: sync
// runs in the reactor root, before any child module compiles.
final class MavenJvmArgs {

  private static final String COMPILER_PLUGIN_KEY =
      Plugin.constructKey(
          LatheLayout.Maven.MAVEN_COMPILER_PLUGIN_GROUP_ID,
          LatheLayout.Maven.MAVEN_COMPILER_PLUGIN_ARTIFACT_ID);
  private static final String FORKED_JVM_ARG_PREFIX = "-J";
  private static final String JAVAC_MODULE_TARGET = "=jdk.compiler/";

  private MavenJvmArgs() {}

  // Each source is parsed on its own, so a flag split across two sources never pairs up. Only
  // MAVEN_OPTS's jdk.compiler grants count: the rest tunes Maven itself. The formatter's grants
  // apply even when the build declares none (a project may run Spotless only in CI).
  static void write(
      final Path workspaceRoot,
      final List<MavenProject> projects,
      final String mavenOpts,
      final List<String> formatterGrants) {
    final List<String> mavenOptsFlags =
        JvmArgsWriter.accessFlags(tokens(mavenOpts)).stream()
            .filter(flag -> flag.contains(JAVAC_MODULE_TARGET))
            .toList();
    JvmArgsWriter.write(
        workspaceRoot,
        Stream.of(
                JvmArgsWriter.accessFlags(forkedJvmArgs(projects)),
                JvmArgsWriter.accessFlags(jvmConfigTokens(workspaceRoot)),
                mavenOptsFlags,
                JvmArgsWriter.accessFlags(formatterGrants))
            .flatMap(List::stream)
            .toList());
  }

  private static List<String> forkedJvmArgs(final List<MavenProject> projects) {
    return projects.stream()
        .map(MavenJvmArgs::compilerPlugin)
        .filter(Objects::nonNull)
        .flatMap(MavenJvmArgs::configurations)
        .flatMap(MavenJvmArgs::compilerArgs)
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

  // <compilerArgs> entries, plus the legacy single-string <compilerArgument> split on whitespace.
  private static Stream<String> compilerArgs(final Xpp3Dom configuration) {
    final Xpp3Dom compilerArgs = configuration.getChild("compilerArgs");
    final Xpp3Dom compilerArgument = configuration.getChild("compilerArgument");
    final Stream<String> listed =
        compilerArgs == null
            ? Stream.empty()
            : Arrays.stream(compilerArgs.getChildren()).map(Xpp3Dom::getValue);
    final Stream<String> single =
        compilerArgument == null || compilerArgument.getValue() == null
            ? Stream.empty()
            : tokens(compilerArgument.getValue()).stream();
    return Stream.concat(listed, single)
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(arg -> !arg.isEmpty());
  }

  // Whitespace-separated tokens of a flag string: MAVEN_OPTS, compilerArgument, a jvm.config line.
  private static List<String> tokens(final String flags) {
    return Arrays.stream(flags.trim().split("\\s+")).filter(token -> !token.isEmpty()).toList();
  }

  private static List<String> jvmConfigTokens(final Path workspaceRoot) {
    final var jvmConfig =
        workspaceRoot
            .resolve(LatheLayout.Maven.MVN_DIR)
            .resolve(LatheLayout.Maven.MVN_JVM_CONFIG_FILE);
    if (!Files.isReadable(jvmConfig)) {
      return List.of();
    }

    try {
      return Files.readAllLines(jvmConfig, StandardCharsets.UTF_8).stream()
          .map(String::trim)
          .filter(line -> !line.startsWith("#"))
          .flatMap(line -> tokens(line).stream())
          .toList();
    } catch (final IOException e) {
      throw new SyncException("lathe:sync failed to read %s".formatted(jvmConfig), e);
    }
  }
}
