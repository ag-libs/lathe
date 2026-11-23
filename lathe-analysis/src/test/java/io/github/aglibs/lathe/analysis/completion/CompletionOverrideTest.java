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

  @Test
  void completion_methodPrefixInRecordBody_offersOverrideStub() {
    final CompletionItem item =
        itemLabeled(fixture.complete("record Test(int x) implements Runnable { ru§ }"), "run")
            .orElseThrow();

    assertThat(item.getInsertText()).contains("@Override", "public void run()");
  }

  // A record's toString, equals and hashCode are implicit, so its own members, yet it may still
  // declare them.
  @Test
  void completion_objectMethodPrefixInRecordBody_offersImplicitMemberStub() {
    assertThat(
            itemLabeled(fixture.complete("record Test(int x) { toS§ }"), "toString")
                .orElseThrow()
                .getInsertText())
        .contains("@Override", "public String toString()");
    assertThat(
            itemLabeled(fixture.complete("record Test(int x) { hashC§ }"), "hashCode")
                .orElseThrow()
                .getInsertText())
        .contains("@Override", "public int hashCode()");
    assertThat(
            itemLabeled(fixture.complete("record Test(int x) { equ§ }"), "equals")
                .orElseThrow()
                .getInsertText())
        .contains("@Override", "public boolean equals(Object ");
  }

  // One the record declares is not offered again, nor an implicit accessor, which overrides
  // nothing.
  @Test
  void completion_recordDeclaringToStringOrAccessor_offersNoStubForIt() {
    assertThat(
            fixture.complete(
                """
                record Test(int x) {
                    public String toString() { return ""; }
                    toS§
                }"""))
        .noneMatch(i -> i.getInsertText() != null && i.getInsertText().contains("toString()"));
    assertThat(fixture.complete("record Test(int value) { val§ }"))
        .noneMatch(i -> i.getInsertText() != null && i.getInsertText().contains("@Override"));
  }

  @Test
  void completion_insideAnInterface_offersADefaultStubButNoneOfObjects() {
    final CompletionItem item =
        itemLabeled(fixture.complete("interface Test extends Runnable { ru§ }"), "run")
            .orElseThrow();

    assertThat(item.getInsertText()).contains("@Override", "default void run()");
    assertThat(fixture.complete("interface Test { toString§ }"))
        .noneMatch(i -> i.getInsertText() != null && i.getInsertText().contains("@Override"));
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
