package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.core.typeindex.TypeKind;
import java.io.IOException;
import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompletionModuleInfoTest {

  @TempDir static java.nio.file.Path tmp;

  private static CompletionFixture fixture;

  @BeforeAll
  static void setup() throws IOException {
    fixture =
        new CompletionFixture(
            CompletionFixture.typeIndex(
                tmp.resolve("index.json"),
                CompletionFixture.typeEntry(
                    "HealthCheck", "io.helidon.health.HealthCheck", TypeKind.INTERFACE),
                CompletionFixture.typeEntry("Config", "io.helidon.config.Config", TypeKind.CLASS),
                CompletionFixture.typeEntry(
                    "CommonUtil", "io.helidon.common.CommonUtil", TypeKind.CLASS),
                CompletionFixture.typeEntry(
                    "ServiceLoader", "java.util.ServiceLoader", TypeKind.CLASS)));
  }

  @AfterAll
  static void teardown() {
    fixture.close();
  }

  private static List<String> labels(final List<CompletionItem> items) {
    return items.stream().map(CompletionItem::getLabel).toList();
  }

  // --- exports ---

  @Test
  void complete_exportsDirective_offersPackageSegments() {
    final List<String> result =
        labels(
            fixture.completeModuleInfo(
                """
                module com.example {
                    exports io.helidon.§
                }
                """));
    assertThat(result).containsExactlyInAnyOrder("health", "config", "common");
  }

  @Test
  void complete_exportsDirective_withTypedPrefix_filtersSegments() {
    final List<String> result =
        labels(
            fixture.completeModuleInfo(
                """
                module com.example {
                    exports io.helidon.hea§
                }
                """));
    assertThat(result).containsExactly("health");
    assertThat(result).doesNotContain("config", "common");
  }

  // --- opens ---

  @Test
  void complete_opensDirective_offersPackageSegments() {
    final List<String> result =
        labels(
            fixture.completeModuleInfo(
                """
                module com.example {
                    opens io.helidon.§
                }
                """));
    assertThat(result).containsExactlyInAnyOrder("health", "config", "common");
  }

  // --- uses ---

  @Test
  void complete_usesDirective_offersTypeSegments() {
    final List<String> result =
        labels(
            fixture.completeModuleInfo(
                """
                module com.example {
                    uses io.helidon.health.§
                }
                """));
    assertThat(result).containsExactly("HealthCheck");
  }

  // --- provides with ---

  @Test
  void complete_providesWithDirective_offersImplementationSegments() {
    final List<String> result =
        labels(
            fixture.completeModuleInfo(
                """
                module com.example {
                    provides io.helidon.health.HealthCheck with io.helidon.§
                }
                """));
    assertThat(result).containsExactlyInAnyOrder("health", "config", "common");
  }

  // --- bare cursor: directive keywords ---

  @Test
  void complete_bareCursorInModuleBody_offersDirectiveKeywords() {
    final List<String> result =
        labels(
            fixture.completeModuleInfo(
                """
                module com.example {
                    §
                }
                """));
    assertThat(result)
        .containsExactlyInAnyOrder("requires", "exports", "opens", "uses", "provides");
  }

  @Test
  void complete_requiresDirective_returnsEmpty() {
    final List<String> result =
        labels(
            fixture.completeModuleInfo(
                """
                module com.example {
                    requires io.helidon.§
                }
                """));
    assertThat(result).isEmpty();
  }
}
