package io.github.aglibs.lathe.server.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.Json;
import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.core.schema.DependencyData;
import io.github.aglibs.lathe.core.schema.JdkSourceData;
import io.github.aglibs.lathe.core.schema.SourceStatus;
import io.github.aglibs.lathe.core.schema.WorkspaceManifestData;
import io.github.aglibs.lathe.server.TestCompiler;
import io.github.aglibs.lathe.server.analysis.CompileMode;
import io.github.aglibs.lathe.server.analysis.SourceLocator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalFileCompilerTest {

  @TempDir Path tmp;

  @Test
  void definition_externalSourceWithManifestClasspath_findsTransitiveDependencySource()
      throws Exception {
    final Path helperSourceRoot = tmp.resolve("sources/helper");
    final Path helperSource = helperSourceRoot.resolve("b/Helper.java");
    Files.createDirectories(helperSource.getParent());
    Files.writeString(helperSource, "package b; public class Helper {}");
    final Path helperJar = tmp.resolve("repo/helper.jar");
    Files.createDirectories(helperJar.getParent());
    TestCompiler.compileToJar(helperJar, tmp.resolve("classes/helper"), List.of(), helperSource);

    final Path usesSourceRoot = tmp.resolve("sources/uses");
    final Path usesSource = usesSourceRoot.resolve("a/Uses.java");
    final String usesContent = "package a; public class Uses { b.Helper helper; }";
    Files.createDirectories(usesSource.getParent());
    Files.writeString(usesSource, usesContent);
    final Path usesJar = tmp.resolve("repo/uses.jar");
    TestCompiler.compileToJar(usesJar, tmp.resolve("classes/uses"), List.of(helperJar), usesSource);

    writeWorkspaceManifest(
        usesJar, usesSourceRoot, List.of(helperJar), helperJar, helperSourceRoot);
    final WorkspaceManifest manifest = WorkspaceManifest.load(tmp);

    try (final var compiler = new ExternalFileCompiler(manifest)) {
      final List<org.eclipse.lsp4j.Diagnostic> diagnostics =
          compiler.analysis().compile(usesSource.toUri().toString(), usesContent, CompileMode.OPEN);
      final Position helperPos =
          SourceLocator.offsetToPosition(usesContent, usesContent.indexOf("Helper"));

      final var definition =
          compiler
              .analysis()
              .definition(usesSource.toUri().toString(), helperPos, List.of(), manifest);

      assertThat(diagnostics).isEmpty();
      assertThat(definition).isPresent();
      assertThat(definition.get().getUri()).isEqualTo(helperSource.toUri().toString());
      assertThat(definition.get().getRange().getStart()).isEqualTo(new Position(0, 24));
    }
  }

  @Test
  void definition_afterHoverTriggersSourceParse_stillFindsExternalType() throws Exception {
    final Path helperSourceRoot = tmp.resolve("sources/helper");
    final Path helperSource = helperSourceRoot.resolve("b/Helper.java");
    Files.createDirectories(helperSource.getParent());
    Files.writeString(helperSource, "package b; public class Helper {}");
    final Path helperJar = tmp.resolve("repo/helper.jar");
    Files.createDirectories(helperJar.getParent());
    TestCompiler.compileToJar(helperJar, tmp.resolve("classes/helper"), List.of(), helperSource);

    final Path usesSourceRoot = tmp.resolve("sources/uses");
    final Path usesSource = usesSourceRoot.resolve("a/Uses.java");
    final String usesContent = "package a; public class Uses { b.Helper helper; }";
    Files.createDirectories(usesSource.getParent());
    Files.writeString(usesSource, usesContent);
    final Path usesJar = tmp.resolve("repo/uses.jar");
    TestCompiler.compileToJar(usesJar, tmp.resolve("classes/uses"), List.of(helperJar), usesSource);

    writeWorkspaceManifest(
        usesJar, usesSourceRoot, List.of(helperJar), helperJar, helperSourceRoot);
    final WorkspaceManifest manifest = WorkspaceManifest.load(tmp);
    final String usesUri = usesSource.toUri().toString();
    final Position helperPos =
        SourceLocator.offsetToPosition(usesContent, usesContent.indexOf("Helper"));

    try (final var compiler = new ExternalFileCompiler(manifest)) {
      compiler.analysis().compile(usesUri, usesContent, CompileMode.OPEN);

      // Hover over Helper: JavadocLocator finds helperSource via manifest.externalSourceDirs()
      // and calls parser.parse(helperSource). In the broken state this resets the compilation
      // FM's CLASS_PATH, causing the subsequent definition lookup to return empty.
      compiler.analysis().hover(usesUri, helperPos, List.of(), manifest);

      final var definition =
          compiler.analysis().definition(usesUri, helperPos, List.of(), manifest);

      assertThat(definition).isPresent();
      assertThat(definition.get().getUri()).isEqualTo(helperSource.toUri().toString());
    }
  }

  @Test
  void compile_manifestClasspathRemoved_reportsMissingDependency() throws Exception {
    final Path helperSourceRoot = tmp.resolve("sources/helper");
    final Path helperSource = helperSourceRoot.resolve("b/Helper.java");
    Files.createDirectories(helperSource.getParent());
    Files.writeString(helperSource, "package b; public class Helper {}");
    final Path helperJar = tmp.resolve("repo/helper.jar");
    Files.createDirectories(helperJar.getParent());
    TestCompiler.compileToJar(helperJar, tmp.resolve("classes/helper"), List.of(), helperSource);

    final Path usesSourceRoot = tmp.resolve("sources/uses");
    final Path usesSource = usesSourceRoot.resolve("a/Uses.java");
    final String usesContent = "package a; public class Uses { b.Helper helper; }";
    Files.createDirectories(usesSource.getParent());
    Files.writeString(usesSource, usesContent);
    final Path usesJar = tmp.resolve("repo/uses.jar");
    TestCompiler.compileToJar(usesJar, tmp.resolve("classes/uses"), List.of(helperJar), usesSource);

    writeWorkspaceManifest(
        usesJar, usesSourceRoot, List.of(helperJar), helperJar, helperSourceRoot);
    try (final var compiler = new ExternalFileCompiler(WorkspaceManifest.load(tmp))) {
      final String uri = usesSource.toUri().toString();
      assertThat(compiler.analysis().compile(uri, usesContent, CompileMode.OPEN)).isEmpty();

      writeWorkspaceManifestWithoutHelper(usesJar, usesSourceRoot);
      compiler.setManifest(WorkspaceManifest.load(tmp));
      final List<Diagnostic> diagnostics =
          compiler.analysis().compile(uri, usesContent, CompileMode.OPEN);

      assertThat(compiler.analysis().isCached(uri)).isTrue();
      assertThat(diagnostics)
          .extracting(d -> d.getMessage().getLeft())
          .anySatisfy(message -> assertThat(message).contains("package b does not exist"));
    }
  }

  @Test
  void compile_manifestJarOutsideDependencyClasspath_resolvesFromWorkspaceJars() throws Exception {
    final Path helperSourceRoot = tmp.resolve("sources/helper");
    final Path helperSource = helperSourceRoot.resolve("b/Helper.java");
    Files.createDirectories(helperSource.getParent());
    Files.writeString(helperSource, "package b; public class Helper {}");
    final Path helperJar = tmp.resolve("repo/helper.jar");
    Files.createDirectories(helperJar.getParent());
    TestCompiler.compileToJar(helperJar, tmp.resolve("classes/helper"), List.of(), helperSource);

    final Path usesSourceRoot = tmp.resolve("sources/uses");
    final Path usesSource = usesSourceRoot.resolve("a/Uses.java");
    final String usesContent = "package a; public class Uses { b.Helper helper; }";
    Files.createDirectories(usesSource.getParent());
    Files.writeString(usesSource, usesContent);
    final Path usesJar = tmp.resolve("repo/uses.jar");
    TestCompiler.compileToJar(usesJar, tmp.resolve("classes/uses"), List.of(helperJar), usesSource);
    writeWorkspaceManifest(usesJar, usesSourceRoot, List.of(), helperJar, helperSourceRoot);

    try (final var compiler = new ExternalFileCompiler(WorkspaceManifest.load(tmp))) {
      final List<Diagnostic> diagnostics =
          compiler.analysis().compile(usesSource.toUri().toString(), usesContent, CompileMode.OPEN);

      assertThat(diagnostics).isEmpty();
    }
  }

  @Test
  void compile_jdkSource_usesPatchModuleOncePerModule() throws Exception {
    final Path sourceRoot = tmp.resolve("jdk");
    final Path source = sourceRoot.resolve("java.base/java/util/LatheJdkProbe.java");
    final Path secondSource = sourceRoot.resolve("java.base/java/util/LatheJdkProbeTwo.java");
    final String content = jdkProbe("LatheJdkProbe");
    final String secondContent = jdkProbe("LatheJdkProbeTwo");
    Files.createDirectories(source.getParent());
    Files.writeString(source, content);
    Files.writeString(secondSource, secondContent);
    writeJdkWorkspaceManifest(sourceRoot);

    try (final var compiler = new ExternalFileCompiler(WorkspaceManifest.load(tmp))) {
      assertThat(compiler.analysis().compile(source.toUri().toString(), content, CompileMode.OPEN))
          .isEmpty();
      assertThat(
              compiler
                  .analysis()
                  .compile(secondSource.toUri().toString(), secondContent, CompileMode.OPEN))
          .isEmpty();
    }
  }

  private void writeWorkspaceManifest(
      final Path usesJar,
      final Path usesSourceRoot,
      final List<Path> usesClasspath,
      final Path helperJar,
      final Path helperSourceRoot)
      throws Exception {
    final Path latheDir = tmp.resolve(LatheLayout.LATHE_DIR);
    Files.createDirectories(latheDir);
    final var deps =
        List.of(
            new DependencyData(
                "com.example:uses:1",
                usesJar.toString(),
                SourceStatus.PRESENT,
                usesSourceRoot.toString(),
                usesClasspath.stream().map(Path::toString).toList()),
            new DependencyData(
                "com.example:helper:1",
                helperJar.toString(),
                SourceStatus.PRESENT,
                helperSourceRoot.toString(),
                List.of()));
    Json.write(
        new WorkspaceManifestData(LatheLayout.SCHEMA_VERSION, tmp.toString(), null, null, deps),
        latheDir.resolve(LatheLayout.WORKSPACE_JSON));
  }

  private void writeWorkspaceManifestWithoutHelper(final Path usesJar, final Path usesSourceRoot)
      throws Exception {
    final Path latheDir = tmp.resolve(LatheLayout.LATHE_DIR);
    Files.createDirectories(latheDir);
    final var deps =
        List.of(
            new DependencyData(
                "com.example:uses:1",
                usesJar.toString(),
                SourceStatus.PRESENT,
                usesSourceRoot.toString(),
                List.of()));
    Json.write(
        new WorkspaceManifestData(LatheLayout.SCHEMA_VERSION, tmp.toString(), null, null, deps),
        latheDir.resolve(LatheLayout.WORKSPACE_JSON));
  }

  private void writeJdkWorkspaceManifest(final Path sourceRoot) throws Exception {
    final Path latheDir = tmp.resolve(LatheLayout.LATHE_DIR);
    Files.createDirectories(latheDir);
    final var jdk = JdkSourceData.present("test", "26", Path.of("/opt/jdk"), null, sourceRoot);
    Json.write(
        new WorkspaceManifestData(LatheLayout.SCHEMA_VERSION, tmp.toString(), null, jdk, List.of()),
        latheDir.resolve(LatheLayout.WORKSPACE_JSON));
  }

  private String jdkProbe(final String className) {
    return """
        package java.util;

        public final class %s extends AbstractSet<Object> {
          public Iterator<Object> iterator() {
            return Collections.emptyIterator();
          }

          public int size() {
            return 0;
          }
        }
        """
        .formatted(className);
  }
}
