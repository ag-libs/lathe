package io.github.aglibs.lathe.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JvmArgsWriterTest {

  @TempDir Path workspaceRoot;

  @Test
  void write_forkedAndJvmConfigGrants_writesDedupedAccessFlags() throws Exception {
    // Forked style: -J flags on the compiler plugin, in both the =-joined and two-token forms, the
    // same grant repeated in an execution, and a heap flag that must not reach the server.
    final var forked =
        projectWithCompilerArgs(
            compilerArgs(
                "-XDcompilePolicy=simple",
                "-J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                "-J-Xmx256m",
                "-J--add-opens",
                "-Jjdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED"),
            compilerArgs("-J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"));
    // In-process style: the same grants given to Maven's own JVM, plus unrelated tuning.
    writeJvmConfig(
        """
        # Error Prone
        -Xmx2g
        --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
        --add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED
        """);

    writer().write(workspaceRoot, List.of(forked, new MavenProject()));

    assertThat(Files.readAllLines(jvmArgs()))
        .containsExactly(
            "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
            "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED");
  }

  @Test
  void write_noGrants_deletesStaleFile() throws Exception {
    Files.createDirectories(jvmArgs().getParent());
    Files.writeString(
        jvmArgs(), "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED\n");
    writeJvmConfig("-Xmx2g\n");
    final var project = projectWithCompilerArgs(compilerArgs("-J-Xmx256m", "-parameters"));

    writer().write(workspaceRoot, List.of(project));

    assertThat(jvmArgs()).doesNotExist();
  }

  private JvmArgsWriter writer() {
    return new JvmArgsWriter(new SystemStreamLog());
  }

  private Path jvmArgs() {
    return workspaceRoot.resolve(LatheLayout.LATHE_DIR).resolve(LatheLayout.JVM_ARGS_FILE);
  }

  private void writeJvmConfig(final String content) throws Exception {
    final var mvnDir = workspaceRoot.resolve(LatheLayout.MVN_DIR);
    Files.createDirectories(mvnDir);
    Files.writeString(mvnDir.resolve(LatheLayout.MVN_JVM_CONFIG_FILE), content);
  }

  // The first configuration is plugin-level; any further ones become executions.
  private static MavenProject projectWithCompilerArgs(
      final Xpp3Dom pluginConfig, final Xpp3Dom... executionConfigs) {
    final var compiler = new Plugin();
    compiler.setGroupId(LatheLayout.MAVEN_COMPILER_PLUGIN_GROUP_ID);
    compiler.setArtifactId(LatheLayout.MAVEN_COMPILER_PLUGIN_ARTIFACT_ID);
    compiler.setConfiguration(pluginConfig);
    for (int i = 0; i < executionConfigs.length; i++) {
      final var execution = new PluginExecution();
      execution.setId("execution-%d".formatted(i));
      execution.setConfiguration(executionConfigs[i]);
      compiler.addExecution(execution);
    }

    final var build = new Build();
    build.addPlugin(compiler);
    final var project = new MavenProject();
    project.setBuild(build);
    return project;
  }

  private static Xpp3Dom compilerArgs(final String... args) throws Exception {
    final var xml = new StringBuilder("<configuration><compilerArgs>");
    for (final var arg : args) {
      xml.append("<arg>%s</arg>".formatted(arg));
    }

    xml.append("</compilerArgs></configuration>");
    return Xpp3DomBuilder.build(new StringReader(xml.toString()));
  }
}
