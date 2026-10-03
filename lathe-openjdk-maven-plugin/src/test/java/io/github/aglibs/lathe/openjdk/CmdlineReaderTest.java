package io.github.aglibs.lathe.openjdk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CmdlineReaderTest {

  // javac-server invocation (path-normalised): launcher prefix + interim --add-exports, then the
  // real
  // javac args after `javacserver.Main --conf=<f>`.
  private static final String JAVACSERVER =
      "/boot/bin/java -Xms32M --limit-modules java.base"
          + " --add-exports java.base/jdk.internal.misc=jdk.compiler.interim"
          + " --module-path /build/interim -cp /build/javacserver_classes"
          + " javacserver.Main --conf=/build/jdk/modules/jdk.compiler/_the.jdk.compiler-javacserver.conf"
          + " -g -Xlint:all -source 28 -target 28 -implicit:none -Xprefer:source"
          + " --add-exports jdk.compiler/com.sun.tools.javac.api=jdk.javadoc"
          + " -XDstringConcat=inline"
          + " --module-source-path \"/build/support/gensrc/*:/jdk/src/*/linux/classes:/jdk/src/*/share/classes\""
          + " --module-path \"\" --system none -encoding utf-8 -Werror"
          + " -cp /build/depend:/build/jdk/modules"
          + " -Xplugin:\"depend /build/jdk/modules/jdk.compiler/_the.jdk.compiler_pubapi\""
          + " -XDmodifiedInputs=/build/x -d /build/jdk/modules -h /build/headers"
          + " @/build/jdk/modules/jdk.compiler/_the.jdk.compiler_batch.filelist";

  @Test
  void read_javacserverCmdline_keepsFidelityDropsMechanics() {
    final var parsed = CmdlineReader.read(JAVACSERVER, "jdk.compiler");

    assertThat(parsed.compilerArgs())
        .containsExactly(
            "-source",
            "28",
            "-target",
            "28",
            "-implicit:none",
            "--add-exports",
            "jdk.compiler/com.sun.tools.javac.api=jdk.javadoc",
            "--patch-module",
            "jdk.compiler=.");
    assertThat(parsed.encoding()).isEqualTo("utf-8");
    // launcher prefix, source-model and build-mechanics flags all gone
    assertThat(parsed.compilerArgs())
        .doesNotContain(
            "--system",
            "none",
            "-Werror",
            "--module-source-path",
            "-g",
            "-Xlint:all",
            "-d",
            "java.base/jdk.internal.misc=jdk.compiler.interim");
  }

  @Test
  void read_moduleSourcePath_resolvesGlobForModuleInOrder() {
    final var parsed = CmdlineReader.read(JAVACSERVER, "jdk.compiler");

    assertThat(parsed.sourceRootPatterns())
        .containsExactly(
            "/build/support/gensrc/jdk.compiler",
            "/jdk/src/jdk.compiler/linux/classes",
            "/jdk/src/jdk.compiler/share/classes");
  }

  @Test
  void read_noModuleSourcePath_yieldsNoRootsButStillSynthesisesPatchModule() {
    final var parsed =
        CmdlineReader.read(
            "/boot/bin/java -cp x com.sun.tools.javac.Main -source 21 -target 21", "java.base");

    assertThat(parsed.sourceRootPatterns()).isEmpty();
    assertThat(parsed.compilerArgs()).containsSequence("--patch-module", "java.base=.");
    assertThat(parsed.compilerArgs()).containsSequence("-source", "21");
  }

  // A plain classpath build-tool compile: boot javac, -source/-target, -cp, -d, @filelist. Keeps
  // the
  // fidelity flags + the classpath; no patch-module or module-source-path synthesis.
  private static final String BUILD_TOOL =
      "/boot/bin/javac -J-Xms32M -g -Xlint:all -source 27 -target 27 -implicit:none -encoding utf-8"
          + " -Werror --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
          + " -cp /build/buildtools/langtools_interim:/build/support/gensrc"
          + " -d /build/buildtools/langtools_tools_classes"
          + " @/build/buildtools/langtools_tools_classes/_the.BUILD_TOOLS_LANGTOOLS_batch.filelist";

  @Test
  void readTool_classpathCompile_keepsFidelityAndClasspathDropsMechanics() {
    final var parsed = CmdlineReader.readTool(BUILD_TOOL);

    assertThat(parsed.compilerArgs())
        .containsExactly(
            "-source",
            "27",
            "-target",
            "27",
            "-implicit:none",
            "-Xlint:-options",
            "--add-exports",
            "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED");
    assertThat(parsed.encoding()).isEqualTo("utf-8");
    assertThat(parsed.classpath())
        .containsExactly("/build/buildtools/langtools_interim", "/build/support/gensrc");
    // no module-system or build-mechanics flags leak in
    assertThat(parsed.compilerArgs())
        .doesNotContain("--patch-module", "-g", "-Xlint:all", "-Werror", "-d", "-cp");
  }
}
