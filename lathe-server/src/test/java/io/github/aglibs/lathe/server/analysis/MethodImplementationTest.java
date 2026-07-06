package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.TestCompiler;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MethodImplementationTest {

  @TempDir private Path tempDir;

  @Test
  void methodImplementations_genericOverride_returnsConcreteExactDeclaration() throws IOException {
    final String serviceContent = "interface Service<T> { T execute(T value); }\n";
    final var serviceSource = Files.writeString(tempDir.resolve("Service.java"), serviceContent);
    final var classDir = tempDir.resolve("classes");
    TestCompiler.compileToDir(classDir, serviceSource);
    final String candidateContent =
        """
        abstract class AbstractService implements Service<String> {
          public abstract String execute(String value);
        }
        class Direct extends AbstractService {
          public String execute(String value) { return value; }
          public String execute(Integer value) { return value.toString(); }
        }
        """;
    final var candidateUri = tempDir.resolve("Direct.java").toUri().toString();

    final ReferenceTarget target;
    try (var targetSession = new SourceAnalysisSession(new TempSourceCompiler())) {
      targetSession.compile(TempSourceCompiler.TEST_URI, serviceContent, 1, CompileMode.OPEN);
      target =
          targetSession.resolveTarget(
              new SourceFeatureRequest(
                  TempSourceCompiler.TEST_URI,
                  serviceContent,
                  0,
                  new Position(0, 27),
                  List.of(tempDir),
                  WorkspaceManifest.empty()));
    }

    try (var candidateSession =
        new SourceAnalysisSession(new TempSourceCompiler(List.of(classDir)))) {
      final List<Location> locations =
          candidateSession.methodImplementations(
              candidateUri, candidateContent, 1, target, Set.of("AbstractService", "Direct"));

      assertThat(locations).hasSize(1);
      assertThat(locations.getFirst().getUri()).isEqualTo(candidateUri);
      assertThat(locations.getFirst().getRange().getStart()).isEqualTo(new Position(4, 16));
      assertThat(locations.getFirst().getRange().getEnd()).isEqualTo(new Position(4, 23));
    }
  }

  @Test
  void methodImplementations_recordComponentAccessor_returnsComponentDeclaration()
      throws IOException {
    final String serviceContent = "interface HasText { String text(); }\n";
    final var serviceSource = Files.writeString(tempDir.resolve("HasText.java"), serviceContent);
    final var classDir = tempDir.resolve("classes");
    TestCompiler.compileToDir(classDir, serviceSource);
    final String recordContent = "record Impl(String text) implements HasText {}\n";
    final var recordUri = tempDir.resolve("Impl.java").toUri().toString();

    final ReferenceTarget target;
    try (var targetSession = new SourceAnalysisSession(new TempSourceCompiler())) {
      targetSession.compile(TempSourceCompiler.TEST_URI, serviceContent, 1, CompileMode.OPEN);
      target =
          targetSession.resolveTarget(
              new SourceFeatureRequest(
                  TempSourceCompiler.TEST_URI,
                  serviceContent,
                  0,
                  new Position(0, 27),
                  List.of(tempDir),
                  WorkspaceManifest.empty()));
    }

    try (var candidateSession =
        new SourceAnalysisSession(new TempSourceCompiler(List.of(classDir)))) {
      final List<Location> locations =
          candidateSession.methodImplementations(
              recordUri, recordContent, 1, target, Set.of("Impl"));

      assertThat(locations).hasSize(1);
      assertThat(locations.getFirst().getUri()).isEqualTo(recordUri);
      assertThat(locations.getFirst().getRange().getStart()).isEqualTo(new Position(0, 19));
      assertThat(locations.getFirst().getRange().getEnd()).isEqualTo(new Position(0, 23));
    }
  }

  @Test
  void implementation_outOfRangePosition_returnsEmpty() {
    final String content = "interface Service { void run(); }\n";
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(TempSourceCompiler.TEST_URI, content, 1, CompileMode.OPEN);
      final var request =
          new SourceFeatureRequest(
              TempSourceCompiler.TEST_URI,
              content,
              0,
              new Position(9999, 0),
              List.of(tempDir),
              WorkspaceManifest.empty());
      assertThat(session.resolveTarget(request)).isNull();
    }
  }

  @Test
  void methodImplementations_nonMethodTarget_returnsEmpty() {
    final var target =
        new ReferenceTarget(
            javax.lang.model.element.ElementKind.CLASS,
            "Service",
            "Service",
            null,
            ReferenceTarget.SearchScope.REACTOR_MODULES,
            List.of(),
            false);

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      assertThat(
              session.methodImplementations(
                  TempSourceCompiler.TEST_URI, "class Service {}", 1, target, Set.of("Service")))
          .isEmpty();
    }
  }

  @Test
  void methodImplementationsTransient_candidateFile_doesNotCacheAnalysis() {
    final String uri = TempSourceCompiler.TEST_URI;
    final String content = "class Impl { void run() {} }";
    final var target =
        new ReferenceTarget(
            javax.lang.model.element.ElementKind.METHOD,
            "Service",
            "run",
            "()V",
            ReferenceTarget.SearchScope.REACTOR_MODULES,
            List.of(),
            false);
    final var compiler = new CountingJavaSourceCompiler();

    try (var session = new SourceAnalysisSession(compiler)) {
      assertThat(session.methodImplementationsTransient(uri, content, target, Set.of("Impl")))
          .isEmpty();
      assertThat(session.methodImplementations(uri, content, 1, target, Set.of("Impl"))).isEmpty();

      assertThat(compiler.count(CompileMode.FAST)).isEqualTo(1);
      assertThat(compiler.count(CompileMode.OPEN)).isEqualTo(1);
    }
  }
}
