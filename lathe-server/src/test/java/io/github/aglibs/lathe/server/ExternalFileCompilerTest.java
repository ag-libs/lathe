package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.LatheLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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

      writeWorkspaceManifest(usesJar, usesSourceRoot, List.of(), helperJar, helperSourceRoot);
      compiler.setManifest(WorkspaceManifest.load(tmp));
      final List<Diagnostic> diagnostics =
          compiler.analysis().compile(uri, usesContent, CompileMode.OPEN);

      assertThat(compiler.analysis().isCached(uri)).isTrue();
      assertThat(diagnostics)
          .extracting(d -> d.getMessage().getLeft())
          .anySatisfy(message -> assertThat(message).contains("package b does not exist"));
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
    final String classpath =
        IntStream.range(0, usesClasspath.size())
            .mapToObj(
                i -> "dependencySource.0.classpath.%d=%s\n".formatted(i, usesClasspath.get(i)))
            .collect(Collectors.joining());
    Files.writeString(
        latheDir.resolve(LatheLayout.WORKSPACE_PROPERTIES),
        """
        schemaVersion=1
        workspaceRoot=%s
        dependencySource.0.gav=com.example:uses:1
        dependencySource.0.jar=%s
        dependencySource.0.status=present
        dependencySource.0.dir=%s
        %sdependencySource.1.gav=com.example:helper:1
        dependencySource.1.jar=%s
        dependencySource.1.status=present
        dependencySource.1.dir=%s
        """
            .formatted(tmp, usesJar, usesSourceRoot, classpath, helperJar, helperSourceRoot));
  }
}
