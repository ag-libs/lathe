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

  private void writeWorkspaceManifestWithoutHelper(final Path usesJar, final Path usesSourceRoot)
      throws Exception {
    final Path latheDir = tmp.resolve(LatheLayout.LATHE_DIR);
    Files.createDirectories(latheDir);
    Files.writeString(
        latheDir.resolve(LatheLayout.WORKSPACE_PROPERTIES),
        """
        schemaVersion=1
        workspaceRoot=%s
        dependencySource.0.gav=com.example:uses:1
        dependencySource.0.jar=%s
        dependencySource.0.status=present
        dependencySource.0.dir=%s
        """
            .formatted(tmp, usesJar, usesSourceRoot));
  }

  private void writeJdkWorkspaceManifest(final Path sourceRoot) throws Exception {
    final Path latheDir = tmp.resolve(LatheLayout.LATHE_DIR);
    Files.createDirectories(latheDir);
    Files.writeString(
        latheDir.resolve(LatheLayout.WORKSPACE_PROPERTIES),
        """
        schemaVersion=1
        workspaceRoot=%s
        jdk.home=/opt/jdk
        jdk.vendor=test
        jdk.version=26
        jdk.sourceStatus=present
        jdk.sourceDir=%s
        """
            .formatted(tmp, sourceRoot));
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
