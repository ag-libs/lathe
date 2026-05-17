package io.github.aglibs.lathe.server.module;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.analysis.CompilationContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompletionTest {

  @TempDir Path tmp;

  private CompilationContext context;
  private String uri;

  @BeforeEach
  void setUp() throws IOException {
    final var latheDir = tmp.resolve(".lathe");
    final var moduleDir = latheDir.resolve("m");
    final var srcDir = tmp.resolve("src");
    Files.createDirectories(moduleDir);
    Files.createDirectories(srcDir);

    uri = srcDir.resolve("Subject.java").toUri().toString();

    final var config =
        new ModuleConfig(
            moduleDir,
            "classes",
            moduleDir,
            null,
            List.of(srcDir),
            List.of(),
            List.of(),
            List.of(),
            null,
            "UTF-8",
            false,
            false,
            null,
            List.of());
    context = new CompilationContext(new ModuleCompiler(config));
  }

  @AfterEach
  void tearDown() {
    context.close();
  }

  // --- member access cases ---

  @Test
  void localVariable_afterDot_returnsTypeMembers() {
    final var source =
        """
        public class Subject {
            public void test() {
                StringBuilder sb = new StringBuilder();
                sb.append("x");
            }
        }
        """;
    final var items = completeAfter(source, "sb.");
    assertThat(labels(items)).contains("append", "length", "charAt", "toString");
  }

  @Test
  void field_afterDot_returnsTypeMembers() {
    final var source =
        """
        public class Subject {
            private final String name = "test";
            public void test() {
                int len = name.length();
            }
        }
        """;
    final var items = completeAfter(source, "name.");
    assertThat(labels(items)).contains("length", "charAt", "substring", "toUpperCase");
  }

  @Test
  void thisAccess_afterDot_returnsClassMembers() {
    final var source =
        """
        public class Subject {
            private int count = 0;
            public void inc() { count++; }
            public void test() {
                this.count = 1;
            }
        }
        """;
    final var items = completeAfter(source, "this.");
    assertThat(labels(items)).contains("count", "inc", "test");
  }

  @Test
  void superAccess_afterDot_returnsObjectMembers() {
    final var source =
        """
        public class Subject {
            public void test() {
                super.hashCode();
            }
        }
        """;
    final var items = completeAfter(source, "super.");
    assertThat(labels(items)).contains("hashCode", "equals", "toString");
  }

  @Test
  void newInstance_afterDot_returnsTypeMembers() {
    final var source =
        """
        public class Subject {
            public void test() {
                new StringBuilder().append("x");
            }
        }
        """;
    final var items = completeAfter(source, "new StringBuilder().");
    assertThat(labels(items)).contains("append", "length");
  }

  @Test
  void chainedCall_afterDot_returnsTypeMembers() {
    final var source =
        """
        public class Subject {
            public void test() {
                "hello".toUpperCase().length();
            }
        }
        """;
    final var items = completeAfter(source, "toUpperCase().");
    assertThat(labels(items)).contains("length", "charAt", "substring");
  }

  // --- item shape ---

  @Test
  void constructors_areNotIncluded() {
    final var source =
        """
        public class Subject {
            public void test() {
                StringBuilder sb = new StringBuilder();
                sb.length();
            }
        }
        """;
    final var items = completeAfter(source, "sb.");
    assertThat(items)
        .extracting(CompletionItem::getKind)
        .doesNotContain(CompletionItemKind.Constructor);
  }

  @Test
  void methods_haveMethodKind() {
    final var source =
        """
        public class Subject {
            public void test() {
                StringBuilder sb = new StringBuilder();
                sb.length();
            }
        }
        """;
    final var items = completeAfter(source, "sb.");
    final var lengthItem = items.stream().filter(i -> "length".equals(i.getLabel())).findFirst();
    assertThat(lengthItem).isPresent();
    assertThat(lengthItem.get().getKind()).isEqualTo(CompletionItemKind.Method);
  }

  @Test
  void methods_detailIsReturnType() {
    final var source =
        """
        public class Subject {
            public void test() {
                StringBuilder sb = new StringBuilder();
                sb.length();
            }
        }
        """;
    final var items = completeAfter(source, "sb.");
    final var lengthItem = items.stream().filter(i -> "length".equals(i.getLabel())).findFirst();
    assertThat(lengthItem).isPresent();
    assertThat(lengthItem.get().getDetail()).isEqualTo("int");
  }

  @Test
  void fields_haveFieldKind() {
    final var source =
        """
        public class Subject {
            public int count = 0;
            public void test() {
                Subject s = this;
                s.count = 1;
            }
        }
        """;
    final var items = completeAfter(source, "s.");
    final var countItem = items.stream().filter(i -> "count".equals(i.getLabel())).findFirst();
    assertThat(countItem).isPresent();
    assertThat(countItem.get().getKind()).isEqualTo(CompletionItemKind.Field);
  }

  // --- generics ---

  @Test
  void genericReceiver_detailShowsConcreteReturnType() {
    final var source =
        """
        import java.util.ArrayList;
        import java.util.List;
        public class Subject {
            public void test() {
                List<Integer> nums = new ArrayList<>();
                nums.get(0);
            }
        }
        """;
    final var items = completeAfter(source, "nums.");
    final var getItem = items.stream().filter(i -> "get".equals(i.getLabel())).findFirst();
    assertThat(getItem).isPresent();
    assertThat(getItem.get().getDetail()).endsWith("Integer");
  }

  // --- fallback / no completion ---

  @Test
  void unresolvedType_returnsEmpty() {
    final var source =
        """
        public class Subject {
            public void test() {
                UnknownType x = null;
                x.method();
            }
        }
        """;
    assertThat(completeAfter(source, "x.")).isEmpty();
  }

  @Test
  void notMemberAccess_returnsEmpty() {
    final var source =
        """
        public class Subject {
            public void test() {
                int x = 1;
            }
        }
        """;
    // position before "x" — not after a dot, sentinel lands inside an identifier
    assertThat(completeAfter(source, "int ")).isEmpty();
  }

  // --- helpers ---

  private List<CompletionItem> completeAfter(final String source, final String marker) {
    final int idx = source.indexOf(marker);
    if (idx < 0) {
      throw new IllegalArgumentException("marker not found: " + marker);
    }

    final int offset = idx + marker.length();
    int line = 0;
    int lineStart = 0;
    for (int i = 0; i < offset; i++) {
      if (source.charAt(i) == '\n') {
        line++;
        lineStart = i + 1;
      }
    }

    return context.complete(uri, source, new Position(line, offset - lineStart));
  }

  private static List<String> labels(final List<CompletionItem> items) {
    return items.stream().map(CompletionItem::getLabel).toList();
  }
}
