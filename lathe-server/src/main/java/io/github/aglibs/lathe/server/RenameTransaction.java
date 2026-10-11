package io.github.aglibs.lathe.server;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

// The files a rename the server produced touches, from the edit until the editor has saved them
// all, so the session compiles them as one change set instead of file by file. Members are matched
// by content (a hash of the text before and after the edit), never by the shape of the change
// events, so this holds for every editor. Owned by the session's worker thread.
final class RenameTransaction {

  enum Action {
    // Not a member, or no longer one: handle the event as usual.
    NORMAL,
    // A member as the rename expects it: no per-file compile.
    SUPPRESS,
    // The last member was saved: commit the transaction.
    COMMIT,
    // Every member is back to its text before the rename and none was saved (the user undid it):
    // drop the transaction.
    ABANDON
  }

  private static final Logger LOG = Logger.getLogger(RenameTransaction.class.getName());

  private final Map<String, Member> members = new HashMap<>();
  // A moved file's old URI: the editor still edits and then closes it, which changes nothing.
  private final Set<String> movedAway = new HashSet<>();
  // Whether the editor has applied the edit at all: a member seen with its edited content.
  private boolean applied;

  // A rename merging into an open transaction keeps the member's original content, since the
  // earlier rename's edits are still unsaved in it. The latest rename decides a URI's role, so
  // renaming a moved file back makes its old URI a member again.
  void add(final String uri, final String original, final String expected) {
    movedAway.remove(uri);
    final Member existing = members.get(uri);
    final var member =
        new Member(existing == null ? hash(original) : existing.originalHash, hash(expected));
    member.atOriginal = existing == null || existing.atOriginal;
    members.put(uri, member);
  }

  void addMovedAway(final String uri) {
    members.remove(uri);
    movedAway.add(uri);
  }

  Action onContent(final String uri, final String content) {
    if (movedAway.contains(uri)) {
      return Action.SUPPRESS;
    }

    final Member member = members.get(uri);
    if (member == null) {
      return Action.NORMAL;
    }

    final int hash = hash(content);
    if (hash == member.expectedHash) {
      applied = true;
      member.atOriginal = false;
      return Action.SUPPRESS;
    }

    if (hash == member.originalHash) {
      member.atOriginal = true;
      return undone() ? Action.ABANDON : Action.SUPPRESS;
    }

    leave(uri, "diverged");
    return Action.NORMAL;
  }

  // The server formats a member just before the editor saves it; the formatted text is what the
  // rename now expects.
  void onFormat(final String uri, final String formatted) {
    final Member member = members.get(uri);
    if (member != null) {
      member.expectedHash = hash(formatted);
    }
  }

  Action onSave(final String uri) {
    final Member member = members.get(uri);
    if (member == null) {
      return movedAway.contains(uri) ? Action.SUPPRESS : Action.NORMAL;
    }

    member.saved = true;
    applied = true;
    return members.values().stream().allMatch(m -> m.saved) ? Action.COMMIT : Action.SUPPRESS;
  }

  void onClose(final String uri) {
    final Member member = members.get(uri);
    if (member != null && !member.saved) {
      leave(uri, "closed unsaved");
    }
  }

  boolean isMember(final String uri) {
    return members.containsKey(uri) || movedAway.contains(uri);
  }

  private boolean undone() {
    return applied && members.values().stream().allMatch(m -> m.atOriginal && !m.saved);
  }

  boolean applied() {
    return applied;
  }

  boolean anySaved() {
    return members.values().stream().anyMatch(m -> m.saved);
  }

  Set<Path> memberPaths() {
    return members.keySet().stream().map(LatheUri::toPath).collect(Collectors.toUnmodifiableSet());
  }

  Set<Path> savedPaths() {
    return members.entrySet().stream()
        .filter(entry -> entry.getValue().saved)
        .map(entry -> LatheUri.toPath(entry.getKey()))
        .collect(Collectors.toUnmodifiableSet());
  }

  Set<String> unsavedUris() {
    return members.entrySet().stream()
        .filter(entry -> !entry.getValue().saved)
        .map(Map.Entry::getKey)
        .collect(Collectors.toUnmodifiableSet());
  }

  int size() {
    return members.size();
  }

  private void leave(final String uri, final String reason) {
    members.remove(uri);
    LOG.fine(() -> "[rename-tx] %s left %s".formatted(uri, reason));
  }

  // Line endings are normalized so an editor's CRLF handling never pushes a member out.
  private static int hash(final String content) {
    return content.replace("\r\n", "\n").hashCode();
  }

  private static final class Member {
    private final int originalHash;
    private int expectedHash;
    private boolean saved;
    // Its content is the text before the rename: not yet applied, or undone.
    private boolean atOriginal = true;

    private Member(final int originalHash, final int expectedHash) {
      this.originalHash = originalHash;
      this.expectedHash = expectedHash;
    }
  }
}
