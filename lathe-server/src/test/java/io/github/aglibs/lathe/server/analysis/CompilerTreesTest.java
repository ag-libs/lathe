package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePathScanner;
import io.github.aglibs.lathe.server.TestCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Runs on every JDK: the multi-release jar gives each one its own CompilerTrees, and both must
// agree on what is a case label.
class CompilerTreesTest {

  @TempDir private Path tmp;

  @Test
  void isCaseLabel_caseConstantAndSelector_onlyConstantIsLabel() throws IOException {
    final Path file =
        Files.writeString(
            tmp.resolve("T.java"),
            "class T { void f(int x) { switch (x) { case 1 -> {} default -> {} } } }");
    final var parsed = TestCompiler.parse(file);
    final List<Boolean> constants = new ArrayList<>();
    final List<Boolean> selectors = new ArrayList<>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitLiteral(final LiteralTree literal, final Void unused) {
        constants.add(CompilerTrees.isCaseLabel(parentOf(), literal));
        return null;
      }

      @Override
      public Void visitIdentifier(final IdentifierTree identifier, final Void unused) {
        if ("x".contentEquals(identifier.getName())) {
          selectors.add(CompilerTrees.isCaseLabel(parentOf(), identifier));
        }

        return null;
      }

      private Tree parentOf() {
        return getCurrentPath().getParentPath().getLeaf();
      }
    }.scan(parsed.cu(), null);

    assertThat(constants).containsExactly(true);
    assertThat(selectors).containsExactly(false);
  }
}
