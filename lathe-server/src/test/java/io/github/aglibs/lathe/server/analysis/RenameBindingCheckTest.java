package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.analysis.testing.TempSourceCompiler;
import io.github.aglibs.lathe.server.analysis.testing.TestJavac;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.ElementKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RenameBindingCheckTest {

  @TempDir private Path tmp;

  // A module that cannot see the edited declaration (another named module of a JPMS reactor) sees
  // the old class, so a renamed call there resolves to nothing; javac names the unresolved symbol
  // after the new name, which is no declaration the call could have been captured by. A capture of
  // a real declaration of the new name is still refused.
  @Test
  void find_renamedCallTheModuleCannotResolve_isNoConflictUnlessARealDeclarationCapturesIt()
      throws Exception {
    final Path api =
        Files.writeString(
            tmp.resolve("Api.java"),
            "public class Api { public String text() { return \"\"; } }\n");
    final Path classes = tmp.resolve("classes");
    TestJavac.compileToDir(classes, api);
    final var target =
        new ReferenceTarget(
            ElementKind.METHOD,
            "Api",
            "text",
            "()",
            ReferenceTarget.SearchScope.REACTOR_MODULES,
            List.of(),
            true,
            -1);

    try (var compiler = new TempSourceCompiler(List.of(classes))) {
      assertThat(
              check(
                  compiler, target, "class Caller { String c(Api api) { return api.text(); } }\n"))
          .isEmpty();
      assertThat(
              check(
                  compiler,
                  target,
                  "class Caller extends Api { String textRenamed(String... s) { return \"\"; }"
                      + " String c() { return text(); } }\n"))
          .hasValueSatisfying(
              reason ->
                  assertThat(reason)
                      .contains(
                          "would then refer to the method `Caller.textRenamed(java.lang.String[])`"));
    }
  }

  // Renames text() to text in a caller only, with the edited Api out of sight.
  private java.util.Optional<String> check(
      final TempSourceCompiler compiler, final ReferenceTarget target, final String caller) {
    final String callerUri = tmp.resolve("Caller.java").toUri().toString();
    final int at = caller.lastIndexOf("text()");
    final var range = new Range(new Position(0, at), new Position(0, at + "text".length()));
    final var file = new RenameBindingCheck.CheckedFile(callerUri, callerUri, List.of(range));
    final var check =
        new RenameBindingCheck.RenameCheck(
            target,
            "textRenamed",
            tmp.resolve("Api.java").toUri().toString(),
            Map.of(callerUri, caller));
    return RenameBindingCheck.find(compiler, check, List.of(file), List.of(file), () -> {});
  }
}
