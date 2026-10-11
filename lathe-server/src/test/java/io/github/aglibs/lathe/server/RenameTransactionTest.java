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
  void onContent_originalOrExpectedSuppressed_handEditLeaves_nonMemberNormal() {
    final var tx = new RenameTransaction();
    tx.add(A, "class A { Old o; }", "class A { New o; }");

    // The editor opens the file as it is on disk, then applies the rename's edit.
    assertThat(tx.onContent(A, "class A { Old o; }")).isEqualTo(SUPPRESS);
    assertThat(tx.onContent(A, "class A { New o; }")).isEqualTo(SUPPRESS);
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
  void applied_onlyOnceAMemberShowsTheEditOrIsSaved_notWhenOpenedUnchanged() {
    final var opened = new RenameTransaction();
    opened.add(A, "class A { Old o; }", "class A { New o; }");
    opened.add(B, "b", "b2");

    // A cancelled preview may open the files, but never applies the edit to them.
    opened.onContent(A, "class A { Old o; }");
    assertThat(opened.applied()).isFalse();
    opened.onContent(A, "class A { New o; }");
    assertThat(opened.applied()).isTrue();

    final var saved = new RenameTransaction();
    saved.add(B, "b", "b2");
    saved.onSave(B);
    assertThat(saved.applied()).isTrue();
  }

  @Test
  void onContent_everyMemberUndoneBeforeAnySave_abandons_partialUndoOrASaveDoesNot() {
    final var tx = new RenameTransaction();
    tx.add(A, "class A { Old o; }", "class A { New o; }");
    tx.add(B, "class B { Old o; }", "class B { New o; }");
    // Opened before the edit is applied: back at the original, but nothing was applied yet.
    assertThat(tx.onContent(A, "class A { Old o; }")).isEqualTo(SUPPRESS);
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
    saved.onSave(B); // a saved member is on disk: undoing the rest does not abandon it
    assertThat(saved.onContent(A, "class A { Old o; }")).isEqualTo(SUPPRESS);
  }

  @Test
  void onFormat_serverFormattingBecomesExpected_memberStaysThroughFormatOnSave() {
    final var tx = new RenameTransaction();
    tx.add(A, "class A { Old o; }", "class A { New o; }");

    tx.onFormat(A, "class A {\n  New o;\n}\n");

    assertThat(tx.onContent(A, "class A {\n  New o;\n}\n")).isEqualTo(SUPPRESS);
    assertThat(tx.onSave(A)).isEqualTo(COMMIT);
  }

  @Test
  void onSave_commitsOnLastMemberSave_nonMemberNormal() {
    final var tx = new RenameTransaction();
    tx.add(A, "a", "a2");
    tx.add(B, "b", "b2");

    assertThat(tx.anySaved()).isFalse();
    assertThat(tx.onSave(A)).isEqualTo(SUPPRESS);
    assertThat(tx.anySaved()).isTrue();
    assertThat(tx.onSave("file:///ws/src/Other.java")).isEqualTo(NORMAL);
    assertThat(tx.onSave(B)).isEqualTo(COMMIT);
    assertThat(tx.savedPaths()).hasSize(2);
    assertThat(tx.unsavedUris()).isEmpty();
  }

  @Test
  void onClose_movedAwayKeepsMembership_unsavedMemberLeaves_savedMemberStays() {
    final var tx = new RenameTransaction();
    tx.add(MOVED, "class Old {}", "class New {}");
    tx.addMovedAway(OLD);
    tx.add(A, "a", "a2");
    tx.add(B, "b", "b2");

    // Neovim edits the moved buffer under its old URI, then closes it and reopens it as the new
    // one.
    assertThat(tx.onContent(OLD, "class New {}")).isEqualTo(SUPPRESS);
    tx.onClose(OLD);
    assertThat(tx.onContent(MOVED, "class New {}")).isEqualTo(SUPPRESS);

    tx.onSave(B);
    tx.onClose(B); // saved: stays for the commit
    tx.onClose(A); // reverted unsaved: leaves

    assertThat(tx.size()).isEqualTo(2);
    assertThat(tx.unsavedUris()).containsExactly(MOVED);
    assertThat(tx.onSave(MOVED)).isEqualTo(COMMIT);
  }

  @Test
  void add_renameMovedFileBackBeforeSaving_latestRoleWins_commitsOnLastSave() {
    final var tx = new RenameTransaction();
    // Old -> New moves the file; renaming it back moves New -> Old.
    tx.add(MOVED, "class Old {}", "class New {}");
    tx.addMovedAway(OLD);
    tx.add(OLD, "class New {}", "class Old {}");
    tx.addMovedAway(MOVED);

    assertThat(tx.isMember(OLD)).isTrue();
    assertThat(tx.size()).isEqualTo(1);
    assertThat(tx.onContent(OLD, "class Old {}")).isEqualTo(SUPPRESS);
    assertThat(tx.onSave(OLD)).isEqualTo(COMMIT);
  }

  @Test
  void add_secondRenameMerges_keepsOriginalTakesNewExpected() {
    final var tx = new RenameTransaction();
    tx.add(A, "class A { Old o; }", "class A { Mid o; }");
    tx.add(A, "class A { Mid o; }", "class A { New o; }");

    assertThat(tx.onContent(A, "class A { Old o; }")).isEqualTo(SUPPRESS);
    assertThat(tx.onContent(A, "class A { New o; }")).isEqualTo(SUPPRESS);
    assertThat(tx.onContent(A, "class A { Mid o; }")).isEqualTo(NORMAL);
  }
}
