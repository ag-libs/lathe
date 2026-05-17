package io.github.aglibs.lathe.server.analysis.completion;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.analysis.WorkbenchFixture;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.junit.jupiter.api.Test;

class CompletionTest extends WorkbenchFixture {

  // --- member completion on local variables ---

  @Test
  void complete_stringLocalVar_includesStringMembers() {
    final var point = inject("stringVar.length()", "stringVar.");
    final var items = complete(point);

    assertThat(items).isNotEmpty();
    assertThat(items)
        .extracting(CompletionItem::getLabel)
        .contains("length", "toUpperCase", "trim");
    assertThat(items)
        .filteredOn(i -> i.getLabel().equals("length"))
        .singleElement()
        .satisfies(
            i -> {
              assertThat(i.getKind()).isEqualTo(CompletionItemKind.Method);
              assertThat(i.getDetail()).isEqualTo("int");
            });
  }

  @Test
  void complete_stringBuilderLocalVar_includesBuilderMembers() {
    final var point = inject("builderVar.append(\"x\")", "builderVar.");
    final var items = complete(point);

    assertThat(items).isNotEmpty();
    assertThat(items).extracting(CompletionItem::getLabel).contains("append", "toString", "length");
  }

  @Test
  void complete_genericListVar_includesListMembers() {
    final var point = inject("intListVar.stream()", "intListVar.");
    final var items = complete(point);

    assertThat(items).isNotEmpty();
    assertThat(items).extracting(CompletionItem::getLabel).contains("get", "size", "stream");
  }

  // --- member completion on fields ---

  @Test
  void complete_fieldAccess_includesStringMembers() {
    final var point = inject("this.labelField.length()", "this.labelField.");
    final var items = complete(point);

    assertThat(items).isNotEmpty();
    assertThat(items).extracting(CompletionItem::getLabel).contains("length", "isEmpty", "strip");
  }

  // --- member completion on chained calls ---

  @Test
  void complete_chainedCall_includesResultMembers() {
    final var point =
        inject("\"hello\".toUpperCase(ENGLISH).length()", "\"hello\".toUpperCase(ENGLISH).");
    final var items = complete(point);

    assertThat(items).isNotEmpty();
    assertThat(items)
        .extracting(CompletionItem::getLabel)
        .contains("length", "substring", "charAt");
  }

  // --- generic type resolution ---

  @Test
  void complete_genericMapVar_includesDeclaredReturnType() {
    final var point = inject("scoreMapVar.get(\"k\")", "scoreMapVar.");
    final var items = complete(point);

    assertThat(items).isNotEmpty();
    assertThat(items).extracting(CompletionItem::getLabel).contains("get", "put", "containsKey");
  }

  // --- enum member access ---

  @Test
  void complete_enumReceiver_includesEnumMethods() {
    final var point = inject("return State.DONE", "return State.");
    final var items = complete(point);

    assertThat(items).isNotEmpty();
    assertThat(items).extracting(CompletionItem::getLabel).contains("DONE", "PENDING", "values");
    assertThat(items)
        .filteredOn(i -> i.getLabel().equals("DONE"))
        .singleElement()
        .extracting(CompletionItem::getKind)
        .isEqualTo(CompletionItemKind.EnumMember);
  }

  // --- inner class member access ---

  @Test
  void complete_innerClassInstance_includesMembers() {
    final var point =
        inject(
            "final var docInstance = new DocHelper()", "final var docInstance = new DocHelper().");
    final var items = complete(point);

    assertThat(items).isNotEmpty();
    assertThat(items).extracting(CompletionItem::getLabel).contains("exercises");
  }
}
