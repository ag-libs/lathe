package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.typeindex.TypeIndexEntry;
import io.github.aglibs.lathe.core.typeindex.TypeKind;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TypeImplementationTest {

  @TempDir private Path sourceRoot;

  @Test
  void typeImplementations_typeCursor_returnsSourceBackedTransitiveSubtypes() throws IOException {
    write("Direct.java", "class Direct implements Service {}\n");
    write("Leaf.java", "class Leaf extends Internal {}\n");
    final var index =
        WorkspaceTypeIndex.build(
            List.of(),
            List.of(
                List.of(
                    entry("Service"),
                    entry("Direct", "Service"),
                    entry("Internal", "Service"),
                    entry("Leaf", "Internal"))));
    final String uri = TempSourceCompiler.TEST_URI;
    final String content = "interface Service {}\n";

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(uri, content, 1, CompileMode.OPEN);

      final List<Location> locations =
          session.typeImplementations(request(content, new Position(0, 12)), index);

      assertThat(locations)
          .extracting(location -> Path.of(location.getUri()).getFileName().toString())
          .containsExactly("Direct.java", "Leaf.java");
    }
  }

  @Test
  void typeImplementations_methodCursor_returnsEmpty() {
    final String uri = TempSourceCompiler.TEST_URI;
    final String content = "class Service { void execute() {} }\n";

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(uri, content, 1, CompileMode.OPEN);

      final List<Location> locations =
          session.typeImplementations(
              request(content, new Position(0, 22)), WorkspaceTypeIndex.empty());

      assertThat(locations).isEmpty();
    }
  }

  private SourceFeatureRequest request(final String content, final Position position) {
    return new SourceFeatureRequest(
        "file:///Test.java", content, 0, position, List.of(sourceRoot), WorkspaceManifest.empty());
  }

  private void write(final String name, final String content) throws IOException {
    Files.writeString(sourceRoot.resolve(name), content);
  }

  private static TypeIndexEntry entry(final String binaryName, final String... directSupertypes) {
    return new TypeIndexEntry(
        binaryName, binaryName, "", TypeKind.CLASS, true, List.of(directSupertypes));
  }
}
