package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;

class CompletionOverrideTest extends CompletionTestSupport {

  @Test
  void completion_objectMethodPrefix_offersToStringOverride() {
    final CompletionItem item =
        itemLabeled(fixture.complete("class Test { toString§ }"), "toString").orElseThrow();

    assertThat(item.getInsertText())
        .contains(
            "@Override", "public String toString()", "throw new UnsupportedOperationException();");
  }

  @Test
  void completion_methodPrefixInClassBody_offersOverrideStub() {
    final CompletionItem item =
        itemLabeled(fixture.complete("class Test implements Runnable { ru§ }"), "run")
            .orElseThrow();

    assertThat(item.getInsertText()).contains("@Override", "public void run()");
  }

  @Test
  void completion_insideMethodBody_noOverrideStub() {
    final List<CompletionItem> items = fixture.complete("class Test { void m() { toString§ } }");

    assertThat(items)
        .noneMatch(i -> i.getInsertText() != null && i.getInsertText().contains("@Override"));
  }

  @Test
  void completion_finalInheritedMethod_notOffered() {
    // Object.getClass is final and must not be offered as an override stub.
    final List<CompletionItem> items = fixture.complete("class Test { getClass§ }");

    assertThat(items)
        .noneMatch(
            i ->
                i.getInsertText() != null
                    && i.getInsertText().contains("@Override")
                    && i.getInsertText().contains("getClass"));
  }
}
