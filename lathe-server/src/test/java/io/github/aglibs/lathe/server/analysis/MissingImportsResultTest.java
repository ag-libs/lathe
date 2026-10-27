package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;

class MissingImportsResultTest {

  // A file with no imports yet, a blank line below its package clause.
  private static final MissingImportsResult RESULT =
      new MissingImportsResult(
          new ImportLayout(List.of(), new Position(1, 0)),
          List.of(
              new MissingImportsResult.MissingImport("Objects", List.of("java.util.Objects")),
              new MissingImportsResult.MissingImport(
                  "List", List.of("java.awt.List", "java.util.List"))));

  // The chosen imports the result offers are placed by its layout, once each; a choice it does not
  // offer adds nothing.
  @Test
  void edits_chosenImports_placesTheOfferedOnesOnce_ignoresTheRest() {
    assertThat(
            RESULT.edits(
                List.of(
                    "java.util.List",
                    "java.util.Objects",
                    "com.example.NotOffered",
                    "java.util.List")))
        .extracting(edit -> edit.getRange().getStart(), TextEdit::getNewText)
        .containsExactly(
            tuple(new Position(1, 0), "\nimport java.util.List;\nimport java.util.Objects;\n"));
  }

  // A document Lathe cannot analyse answers the empty result, whose layout would put an import at
  // the
  // very top: nothing is added instead.
  @Test
  void edits_emptyResult_addsNothing() {
    assertThat(MissingImportsResult.empty().edits(List.of("java.util.List"))).isEmpty();
    assertThat(RESULT.edits(List.of())).isEmpty();
  }
}
