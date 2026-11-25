package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

// CQ-0079: a block typed line by line is not closed yet when completion asks for a member. The
// module's analysis is the file's before the edit, as in the editor, so the receiver resolves on
// the edited text through the module's own compiler; the class's other members follow the block.
class CompletionUnclosedBlockTest extends ServiceTestBase {

  private static final String MARKER = "§";

  private static final String BEFORE =
      """
      package com.app;
      class Use {
        static class Nested {
          int width() {
            return 0;
          }
        }

        int next;

        void m(Object o, Nested nested) {
          next = 1;
        }

        void n() {}
      }
      """;

  @ParameterizedTest(name = "{0}")
  @MethodSource("unclosedBlocks")
  void completion_memberAccessInUnclosedBlockAfterAnEdit_offersTheReceiversMembers(
      final String label, final String block, final String member) throws Exception {
    final Path use =
        TestCompiler.writeMirroredModule(
                tmp, "app", Map.of("com/app/Use.java", BEFORE), List.of(), 1_000L)
            .get(0);
    host.initialize(tmp);
    awaitStartup();
    openDoc(use, BEFORE);
    awaitWorkerIdle();

    final String marked =
        BEFORE.replace("    next = 1;\n", "    %s\n    next = 1;\n".formatted(block));
    final int at = marked.indexOf(MARKER);
    final String edited = marked.replace(MARKER, "");
    change(use, edited, 2);

    final Either<List<CompletionItem>, CompletionList> items =
        service
            .completion(new CompletionParams(documentOf(use), position(edited, at)))
            .get(TIMEOUT_S, TimeUnit.SECONDS);

    assertThat(items.isLeft() ? items.getLeft() : items.getRight().getItems())
        .as(label)
        .extracting(CompletionItem::getLabel)
        .anySatisfy(item -> assertThat(item).startsWith(member));
  }

  // A brace left open after the cursor, in another method, is that method's: the member access
  // before it still resolves a type the class declares after both.
  @Test
  void completion_braceLeftOpenInALaterMethod_offersTheReceiversMembers() throws Exception {
    final String source =
        """
        package com.app;
        class Use {
          void m(Later later) {
            later.§
          }

          void k(boolean b) {
            if (b) {
          }

          static class Later {
            int width() {
              return 0;
            }
          }
        }
        """;
    final int at = source.indexOf(MARKER);
    final String edited = source.replace(MARKER, "");
    final Path use =
        TestCompiler.writeMirroredModule(
                tmp, "app", Map.of("com/app/Use.java", BEFORE), List.of(), 1_000L)
            .get(0);
    host.initialize(tmp);
    awaitStartup();
    openDoc(use, BEFORE);
    awaitWorkerIdle();
    change(use, edited, 2);

    final Either<List<CompletionItem>, CompletionList> items =
        service
            .completion(new CompletionParams(documentOf(use), position(edited, at)))
            .get(TIMEOUT_S, TimeUnit.SECONDS);

    assertThat(items.isLeft() ? items.getLeft() : items.getRight().getItems())
        .extracting(CompletionItem::getLabel)
        .anySatisfy(item -> assertThat(item).startsWith("width"));
  }

  static Stream<Arguments> unclosedBlocks() {
    return Stream.of(
        Arguments.of("switchOnAJdkType", "switch (o) {\n      case String s -> s.§", "isEmpty"),
        Arguments.of(
            "switchOnTheClassesOwnType", "switch (o) {\n      case Nested w -> w.§", "width"),
        Arguments.of("colonCase", "switch (o) {\n      case Nested w:\n        w.§", "width"),
        Arguments.of("ifBlock", "if (next > 0) {\n      nested.§", "width"),
        Arguments.of("lambdaBlock", "Runnable r = () -> {\n      nested.§", "width"));
  }

  private static Position position(final String content, final int offset) {
    final int line = (int) content.substring(0, offset).chars().filter(c -> c == '\n').count();
    return new Position(line, offset - (content.lastIndexOf('\n', offset - 1) + 1));
  }
}
