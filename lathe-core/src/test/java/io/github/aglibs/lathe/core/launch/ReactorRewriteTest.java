package io.github.aglibs.lathe.core.launch;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.schema.OutputData;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ReactorRewriteTest {

  private static final Path WORKSPACE = Path.of("/workspace");

  // Outputs as a Maven front-end (app, root module "") and a Gradle one (lib) would list them.
  private static final ReactorRewrite REWRITE =
      ReactorRewrite.of(
          WORKSPACE,
          List.of(
              new OutputData("app/target/classes", "app", "classes"),
              new OutputData("app/target/test-classes", "app", "test-classes"),
              new OutputData("app/target/app-1.0.jar", "app", "classes"),
              new OutputData("app/target/app-1.0-tests.jar", "app", "test-classes"),
              new OutputData("target/classes", "", "classes"),
              new OutputData("lib/build/classes/java/main", "lib", "classes"),
              new OutputData("lib/build/libs/lib.jar", "lib", "classes")));

  @Test
  void toLathe_listedOutputs_rewriteToTheirMirror() {
    assertThat(REWRITE.toLathe("/workspace/app/target/classes"))
        .isEqualTo("/workspace/.lathe/app/classes");
    assertThat(REWRITE.toLathe("/workspace/app/target/test-classes"))
        .isEqualTo("/workspace/.lathe/app/test-classes");
    assertThat(REWRITE.toLathe("/workspace/app/target/app-1.0.jar"))
        .isEqualTo("/workspace/.lathe/app/classes");
    assertThat(REWRITE.toLathe("/workspace/app/target/app-1.0-tests.jar"))
        .isEqualTo("/workspace/.lathe/app/test-classes");
    assertThat(REWRITE.toLathe("/workspace/target/classes")).isEqualTo("/workspace/.lathe/classes");
    assertThat(REWRITE.toLathe("/workspace/lib/build/classes/java/main"))
        .isEqualTo("/workspace/.lathe/lib/classes");
    assertThat(REWRITE.toLathe(Path.of("/workspace/lib/build/libs/lib.jar")))
        .isEqualTo(Path.of("/workspace/.lathe/lib/classes"));
  }

  @Test
  void toLathe_unlistedPaths_passThroughUnchanged() {
    assertThat(REWRITE.toLathe("/home/user/.m2/repo/lib.jar"))
        .isEqualTo("/home/user/.m2/repo/lib.jar");
    // A jar committed in the repo, and an OpenJDK build-tool output: in the workspace, not listed.
    assertThat(REWRITE.toLathe("/workspace/libs/vendor.jar"))
        .isEqualTo("/workspace/libs/vendor.jar");
    assertThat(REWRITE.toLathe(Path.of("/workspace/build/conf/buildtools/tools_classes")))
        .isEqualTo(Path.of("/workspace/build/conf/buildtools/tools_classes"));
    assertThat(ReactorRewrite.none().toLathe("/workspace/app/target/classes"))
        .isEqualTo("/workspace/app/target/classes");
  }

  @Test
  void toLathe_listAndMap_rewriteEachEntryKeepingOrderAndKeys() {
    final List<String> list =
        REWRITE.toLathe(List.of("/workspace/app/target/classes", "/home/user/.m2/repo/lib.jar"));
    final Map<String, String> map =
        REWRITE.toLathe(Map.of("com.example.app", "/workspace/app/target/test-classes"));

    assertThat(list)
        .containsExactly("/workspace/.lathe/app/classes", "/home/user/.m2/repo/lib.jar");
    assertThat(map)
        .containsExactly(Map.entry("com.example.app", "/workspace/.lathe/app/test-classes"));
  }
}
