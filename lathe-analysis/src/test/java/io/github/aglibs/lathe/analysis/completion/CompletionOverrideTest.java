package io.github.aglibs.lathe.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.TextEdit;
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

  // The stub names its return and thrown types as the file can, and the item imports each.
  @Test
  void completion_stubNamingUnimportedTypes_importsEach() {
    final CompletionItem item =
        itemLabeled(
                fixture.complete(
                    """
                    package com.example;
                    interface Store { java.util.List<String> all() throws java.io.IOException; }
                    class Test implements Store { al§ }"""),
                "all")
            .orElseThrow();

    assertThat(item.getInsertText()).contains("public List<String> all() throws IOException");
    assertThat(item.getAdditionalTextEdits())
        .extracting(TextEdit::getNewText)
        .anySatisfy(text -> assertThat(text).contains("import java.util.List;"))
        .anySatisfy(text -> assertThat(text).contains("import java.io.IOException;"));
  }

  @Test
  void completion_stubNamingOnlyVisibleTypes_importsNothing() {
    final CompletionItem item =
        itemLabeled(fixture.complete("class Test { toString§ }"), "toString").orElseThrow();

    assertThat(item.getAdditionalTextEdits()).isNullOrEmpty();
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
