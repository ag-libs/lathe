package io.github.aglibs.lathe.core.launch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ReactorRewriteTest {

  @Test
  void toLathe_targetClasses_rewritesToLatheClasses() {
    final var workspace = Path.of("/workspace");

    final String rewritten = ReactorRewrite.toLathe("/workspace/app/target/classes", workspace);

    assertThat(rewritten).isEqualTo("/workspace/.lathe/app/classes");
  }

  @Test
  void toLathe_targetTestClasses_rewritesToLatheTestClasses() {
    final var workspace = Path.of("/workspace");

    final String rewritten =
        ReactorRewrite.toLathe("/workspace/app/target/test-classes", workspace);

    assertThat(rewritten).isEqualTo("/workspace/.lathe/app/test-classes");
  }

  @Test
  void toLathe_externalJar_leavesUnchanged() {
    final var workspace = Path.of("/workspace");

    final String rewritten = ReactorRewrite.toLathe("/home/user/.m2/repo/lib.jar", workspace);

    assertThat(rewritten).isEqualTo("/home/user/.m2/repo/lib.jar");
  }

  @Test
  void toLathe_reactorMainJar_rewritesToLatheClasses() {
    final var workspace = Path.of("/workspace");

    final String rewritten = ReactorRewrite.toLathe("/workspace/app/target/app-1.0.jar", workspace);

    assertThat(rewritten).isEqualTo("/workspace/.lathe/app/classes");
  }

  @Test
  void toLathe_reactorTestJar_rewritesToLatheTestClasses() {
    final var workspace = Path.of("/workspace");

    final String rewritten =
        ReactorRewrite.toLathe("/workspace/app/target/app-1.0-tests.jar", workspace);

    assertThat(rewritten).isEqualTo("/workspace/.lathe/app/test-classes");
  }

  @Test
  void toLathe_pathList_rewritesMatchingEntries() {
    final var workspace = Path.of("/workspace");

    final List<String> rewritten =
        ReactorRewrite.toLathe(
            List.of("/workspace/app/target/classes", "/home/user/.m2/repo/lib.jar"), workspace);

    assertThat(rewritten)
        .containsExactly("/workspace/.lathe/app/classes", "/home/user/.m2/repo/lib.jar");
  }

  @Test
  void toLathe_pathMap_rewritesValues() {
    final var workspace = Path.of("/workspace");

    final Map<String, String> rewritten =
        ReactorRewrite.toLathe(
            Map.of("com.example.app", "/workspace/app/target/classes"), workspace);

    assertThat(rewritten).containsEntry("com.example.app", "/workspace/.lathe/app/classes");
  }
}
