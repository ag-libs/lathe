package io.github.aglibs.lathe.server;

import static io.github.aglibs.lathe.server.RenameTransaction.Action.ABANDON;
import static io.github.aglibs.lathe.server.RenameTransaction.Action.COMMIT;
import static io.github.aglibs.lathe.server.RenameTransaction.Action.NORMAL;
import static io.github.aglibs.lathe.server.RenameTransaction.Action.SUPPRESS;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RenameTransactionTest {

  private static final String A = "file:///ws/src/A.java";
  private static final String B = "file:///ws/src/B.java";
  private static final String OLD = "file:///ws/src/Old.java";
  private static final String MOVED = "file:///ws/src/New.java";

  @Test
  void onContent_dormantUntilApplied_thenEditedSuppressed_handEditLeaves_nonMemberNormal() {
    final var tx = new RenameTransaction();
    tx.add(A, "class A { Old o; }", "class A { New o; }");

    // The editor opens the file as it is on disk: not applied yet, so an ordinary open.
    assertThat(tx.onContent(A, "class A { Old o; }")).isEqualTo(NORMAL);
    assertThat(tx.isMember(A)).isFalse();
    assertThat(tx.memberPaths()).isEmpty();
    // It applies the rename's edit: from now on the file waits for the commit.
    assertThat(tx.onContent(A, "class A { New o; }")).isEqualTo(SUPPRESS);
    assertThat(tx.isMember(A)).isTrue();
    assertThat(tx.memberPaths()).hasSize(1);
    assertThat(tx.onContent(B, "class B {}")).isEqualTo(NORMAL);

    // The user types into it: from now on it is an ordinary document.
    assertThat(tx.onContent(A, "class A { New o; int typed; }")).isEqualTo(NORMAL);
    assertThat(tx.onContent(A, "class A { New o; }")).isEqualTo(NORMAL);
    assertThat(tx.size()).isZero();
  }

  @Test
  void onContent_crlfFileMatchingAfterNormalization_suppressed() {
    final var tx = new RenameTransaction();
    tx.add(A, "class A {\n  Old o;\n}\n", "class A {\n  New o;\n}\n");

    assertThat(tx.onContent(A, "class A {\r\n  New o;\r\n}\r\n")).isEqualTo(SUPPRESS);
  }

  @Test
  void applied_onlyOnceAMemberShowsOrSavesTheEdit_notWhenOpenedOrSavedUnchanged() {
    final var opened = new RenameTransaction();
    opened.add(A, "class A { Old o; }", "class A { New o; }");
    opened.add(B, "b", "b2");

    // A cancelled preview may open the files, but never applies the edit to them.
    opened.onContent(A, "class A { Old o; }");
    assertThat(opened.onSave(B, null)).isEqualTo(NORMAL);
    assertThat(opened.applied()).isFalse();
    opened.onContent(A, "class A { New o; }");
    assertThat(opened.applied()).isTrue();

    // An editor that applies the edit and saves it without a change event.
    final var saved = new RenameTransaction();
    saved.add(B, "b", "b2");
    assertThat(saved.onSave(B, "b2")).isEqualTo(COMMIT);
    assertThat(saved.applied()).isTrue();
  }

  @Test
  void onSave_savedTextDivergingFromTheRename_leaves() {
    final var tx = new RenameTransaction();
    tx.add(A, "a", "a2");
    tx.add(B, "b", "b2");
    tx.onContent(A, "a2");

    // B is saved with text that is neither its original nor the rename's: a hand edit.
    assertThat(tx.onSave(B, "b-edited")).isEqualTo(NORMAL);
    assertThat(tx.size()).isEqualTo(1);
    assertThat(tx.onSave(A, "a2")).isEqualTo(COMMIT);
  }

  @Test
  void onContent_everyMemberUndoneBeforeAnySave_abandons_partialUndoOrASaveDoesNot() {
    final var tx = new RenameTransaction();
    tx.add(A, "class A { Old o; }", "class A { New o; }");
    tx.add(B, "class B { Old o; }", "class B { New o; }");
    tx.onContent(A, "class A { New o; }");
    tx.onContent(B, "class B { New o; }");

    // `u` in A only: B still carries the rename.
    assertThat(tx.onContent(A, "class A { Old o; }")).isEqualTo(SUPPRESS);
    // `u` in B too: nothing of the rename is left.
    assertThat(tx.onContent(B, "class B { Old o; }")).isEqualTo(ABANDON);

    final var saved = new RenameTransaction();
    saved.add(A, "class A { Old o; }", "class A { New o; }");
    saved.add(B, "class B { Old o; }", "class B { New o; }");
    saved.onContent(A, "class A { New o; }");
    saved.onSave(B, null); // a saved member is on disk: undoing the rest does not abandon it
    assertThat(saved.onContent(A, "class A { Old o; }")).isEqualTo(SUPPRESS);
  }

  @Test
  void onFormat_serverFormattingBecomesExpected_memberStaysThroughFormatOnSave() {
    final var tx = new RenameTransaction();
    tx.add(A, "class A { Old o; }", "class A { New o; }");

    tx.onFormat(A, "class A {\n  New o;\n}\n");

    assertThat(tx.onContent(A, "class A {\n  New o;\n}\n")).isEqualTo(SUPPRESS);
    assertThat(tx.onSave(A, "class A {\n  New o;\n}\n")).isEqualTo(COMMIT);
  }

  @Test
  void onSave_commitsOnLastMemberSave_nonMemberNormal() {
    final var tx = new RenameTransaction();
    tx.add(A, "a", "a2");
    tx.add(B, "b", "b2");
    tx.onContent(A, "a2");

    assertThat(tx.anySaved()).isFalse();
    assertThat(tx.onSave(A, null)).isEqualTo(SUPPRESS);
    assertThat(tx.anySaved()).isTrue();
    assertThat(tx.onSave("file:///ws/src/Other.java", null)).isEqualTo(NORMAL);
    assertThat(tx.onSave(B, "b2")).isEqualTo(COMMIT);
    assertThat(tx.savedPaths()).hasSize(2);
    assertThat(tx.unsavedUris()).isEmpty();
  }

  @Test
  void onClose_movedAwayKeepsMembership_unsavedMemberLeaves_savedMemberStays() {
    final var tx = new RenameTransaction();
    tx.add(MOVED, "class Old {}", "class New {}");
    tx.addMovedAway(OLD, MOVED);
    tx.add(A, "a", "a2");
    tx.add(B, "b", "b2");

    // Neovim edits the moved buffer under its old URI -- which applies the rename -- then closes it
    // and reopens it as the new one.
    assertThat(tx.onContent(OLD, "class Old {}")).isEqualTo(NORMAL);
    assertThat(tx.onContent(OLD, "class New {}")).isEqualTo(SUPPRESS);
    assertThat(tx.applied()).isTrue();
    tx.onClose(OLD);
    assertThat(tx.onContent(MOVED, "class New {}")).isEqualTo(SUPPRESS);

    tx.onSave(B, null);
    tx.onClose(B); // saved: stays for the commit
    tx.onClose(A); // reverted unsaved: leaves

    assertThat(tx.size()).isEqualTo(2);
    assertThat(tx.unsavedUris()).containsExactly(MOVED);
    assertThat(tx.onSave(MOVED, null)).isEqualTo(COMMIT);
  }

  @Test
  void add_renameMovedFileBackBeforeSaving_latestRoleWins_commitsOnLastSave() {
    final var tx = new RenameTransaction();
    // Old -> New moves the file; renaming it back moves New -> Old.
    tx.add(MOVED, "class Old {}", "class New {}");
    tx.addMovedAway(OLD, MOVED);
    tx.add(OLD, "class New {}", "class Old {}");
    tx.addMovedAway(MOVED, OLD);

    assertThat(tx.size()).isEqualTo(1);
    assertThat(tx.onContent(OLD, "class Old {}")).isEqualTo(SUPPRESS);
    assertThat(tx.isMember(OLD)).isTrue();
    assertThat(tx.onSave(OLD, null)).isEqualTo(COMMIT);
  }

  @Test
  void add_secondRenameMerges_keepsOriginalTakesNewExpected() {
    final var tx = new RenameTransaction();
    tx.add(A, "class A { Old o; }", "class A { Mid o; }");
    tx.add(A, "class A { Mid o; }", "class A { New o; }");

    assertThat(tx.onContent(A, "class A { New o; }")).isEqualTo(SUPPRESS);
    assertThat(tx.onContent(A, "class A { Old o; }")).isEqualTo(ABANDON);

    final var midway = new RenameTransaction();
    midway.add(A, "class A { Old o; }", "class A { Mid o; }");
    midway.add(A, "class A { Mid o; }", "class A { New o; }");
    // The first rename's text is neither the original nor the latest edit: a hand edit.
    assertThat(midway.onContent(A, "class A { Mid o; }")).isEqualTo(NORMAL);
  }
}
