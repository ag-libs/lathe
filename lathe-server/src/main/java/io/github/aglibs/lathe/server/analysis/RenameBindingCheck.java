package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

// Whether a rename keeps what every name means. A rename adds one declaration of the new name and
// removes the old one, so a name means something else afterwards exactly when the uses of the
// renamed declaration, found again in the edited text, differ from the sites the rename edits: an
// extra one is an existing use the new name captures, a missing one a renamed site that now means
// something else. A renamed site that no longer resolves is a compile error the diagnostics report.
public final class RenameBindingCheck {

  private RenameBindingCheck() {}

  // A file as the rename leaves it: its text, with the rename's edits applied as the client applies
  // them, and the spans the edits replaced, to move the old text's offsets to the new.
  private record EditedFile(String uri, String content, List<Span> renamed, int newLength) {

    private EditedFile {
      ValidCheck.check().notBlank(uri, "uri").notNull(content, "content").validate();
      renamed = renamed.stream().sorted(Comparator.comparingInt(Span::start)).toList();
    }

    // The file with each renamed range replaced by the new name; a file the rename does not edit
    // keeps its text.
    static EditedFile of(
        final String uri, final String content, final List<Range> ranges, final String newName) {
      final List<Span> renamed = ranges.stream().map(range -> span(content, range)).toList();
      final List<TextEdit> edits =
          ranges.stream().map(range -> new TextEdit(range, newName)).toList();
      return new EditedFile(
          uri, SourceLocator.applyEdits(content, edits), renamed, newName.length());
    }

    // Where an offset of the text before the rename lands after it.
    private int shifted(final int oldOffset) {
      return oldOffset
          + renamed.stream()
              .filter(span -> span.start() < oldOffset)
              .mapToInt(span -> newLength - span.length())
              .sum();
    }

    TransientSource source() {
      return new TransientSource(uri, content);
    }

    private Set<Integer> renamedStarts() {
      return renamed.stream()
          .map(span -> shifted(span.start()))
          .collect(Collectors.toUnmodifiableSet());
    }

    private static Span span(final String content, final Range range) {
      final int start = offset(content, range.getStart());
      return new Span(start, offset(content, range.getEnd()) - start);
    }

    private static int offset(final String content, final Position position) {
      return SourceLocator.toOffset(content, position.getLine(), position.getCharacter());
    }
  }

  // A replaced span of the text before the rename.
  private record Span(int start, int length) {

    private Span {
      ValidCheck.check().assertTrue(start >= 0 && length >= 0, "span").validate();
    }
  }

  // The renamed declaration under its new name, a type's binary name included; a local keeps its
  // identity by where its declaration starts, which moves with the renamed sites before it.
  private static ReferenceTarget renamedTarget(
      final ReferenceTarget target, final String newName, final EditedFile declaringFile) {
    final boolean type = target.kind().isClass() || target.kind().isInterface();
    return new ReferenceTarget(
        target.kind(),
        type ? renamedBinaryName(target.qualifiedName(), newName) : target.qualifiedName(),
        newName,
        target.erasedDescriptor(),
        target.scope(),
        target.overriddenDeclarers(),
        target.overrideFamilyBounded(),
        target.hasDeclarationIdentity() && declaringFile != null
            ? declaringFile.shifted((int) target.declarationOffset())
            : target.declarationOffset());
  }

  // A binary name with its last segment, after the package's `.` or the outer type's `$`, renamed.
  private static String renamedBinaryName(final String binaryName, final String newName) {
    final int end = Math.max(binaryName.lastIndexOf('.'), binaryName.lastIndexOf('$'));
    return "%s%s".formatted(binaryName.substring(0, end + 1), newName);
  }

  // A rename to check, shared by every module that sees the symbol: what is renamed, the file
  // declaring a local (whose identity is where it is declared), and the open documents' text, which
  // a file's text on disk would not be.
  public record RenameCheck(
      ReferenceTarget target,
      String newName,
      String declaringUri,
      Map<String, String> openContents) {

    public RenameCheck {
      ValidCheck.check()
          .notNull(target, "target")
          .notBlank(newName, "newName")
          .notBlank(declaringUri, "declaringUri")
          .validate();
      openContents = Map.copyOf(openContents);
    }
  }

  // A file to attribute: where its text is, the file it is attributed as (a moved type's new one),
  // and the ranges the rename edits in it.
  public record CheckedFile(String uri, String compiledUri, List<Range> renamed) {

    public CheckedFile {
      ValidCheck.check().notBlank(uri, "uri").notBlank(compiledUri, "compiledUri").validate();
      renamed = List.copyOf(renamed);
    }

    public boolean edited() {
      return !renamed.isEmpty();
    }
  }

  // Untouched files attributed per javac task with the edited ones: a common new name can bring in
  // thousands, and one task holding them all would hold all their trees at once.
  private static final int UNTOUCHED_PER_TASK = 100;

  // The first name in a module's checked files whose meaning the rename would change, as the reason
  // to refuse; the files are attributed as the rename leaves them, with the edited files the module
  // sees (its own and its dependencies'), so the renamed declarations are what the rest see.
  public static Optional<String> find(
      final JavaSourceCompiler compiler,
      final RenameCheck check,
      final List<CheckedFile> visibleEdits,
      final List<CheckedFile> checked,
      final CancelChecker cancelChecker) {
    final List<EditedFile> edited = editedFiles(visibleEdits.stream(), check);
    final List<EditedFile> untouched =
        editedFiles(
            checked.stream()
                .filter(file -> !file.edited())
                .sorted(Comparator.comparing(CheckedFile::compiledUri)),
            check);
    final Map<String, EditedFile> byUri =
        Stream.concat(edited.stream(), untouched.stream())
            .collect(Collectors.toUnmodifiableMap(EditedFile::uri, Function.identity()));
    final ReferenceTarget renamed =
        renamedTarget(check.target(), check.newName(), byUri.get(check.declaringUri()));
    final Set<String> checkedEdits =
        checked.stream()
            .filter(CheckedFile::edited)
            .map(CheckedFile::compiledUri)
            .collect(Collectors.toUnmodifiableSet());
    // Every batch holds the visible edited files; this module's edited files are checked in the
    // first, its untouched ones in their own.
    final List<List<EditedFile>> batches =
        untouched.isEmpty()
            ? List.of(List.of())
            : IntStream.iterate(
                    0, from -> from < untouched.size(), from -> from + UNTOUCHED_PER_TASK)
                .mapToObj(
                    from ->
                        untouched.subList(
                            from, Math.min(untouched.size(), from + UNTOUCHED_PER_TASK)))
                .toList();
    for (int i = 0; i < batches.size(); i++) {
      final Set<String> checking =
          Stream.concat(
                  i == 0 ? checkedEdits.stream() : Stream.<String>empty(),
                  batches.get(i).stream().map(EditedFile::uri))
              .collect(Collectors.toUnmodifiableSet());
      final Optional<String> conflict =
          compiler.analyzeDetached(
              Stream.concat(edited.stream(), batches.get(i).stream())
                  .map(EditedFile::source)
                  .toList(),
              cancelChecker,
              analyses ->
                  analyses.stream()
                      .filter(analysis -> checking.contains(analysis.uri()))
                      .flatMap(
                          analysis ->
                              changedMeaning(
                                  analysis,
                                  byUri.get(analysis.uri()),
                                  renamed,
                                  check.target().simpleName()))
                      .findFirst());
      if (conflict.isPresent()) {
        return conflict;
      }
    }

    return Optional.empty();
  }

  private static List<EditedFile> editedFiles(
      final Stream<CheckedFile> files, final RenameCheck check) {
    return files.flatMap(file -> editedFile(file, check).stream()).toList();
  }

  // The file as the rename leaves it, from the open document's text or else the file on disk; a
  // file gone from disk is left out.
  private static Optional<EditedFile> editedFile(final CheckedFile file, final RenameCheck check) {
    final String open = check.openContents().get(file.uri());
    try {
      final String content =
          open != null ? open : Files.readString(Path.of(URI.create(file.uri())));
      return Optional.of(
          EditedFile.of(file.compiledUri(), content, file.renamed(), check.newName()));
    } catch (final IOException e) {
      return Optional.empty();
    }
  }

  private static Stream<String> changedMeaning(
      final TransientAnalysis transientAnalysis,
      final EditedFile file,
      final ReferenceTarget renamed,
      final String oldName) {
    final var analysis = transientAnalysis.analysis();
    final Set<Integer> expected = file.renamedStarts();
    final Map<Integer, ReferenceRole> found = uses(analysis, renamed, file);
    final String renaming =
        "`%s` cannot be renamed to `%s`".formatted(oldName, renamed.simpleName());
    final Stream<String> captured =
        found.keySet().stream()
            .filter(start -> !expected.contains(start))
            .sorted()
            .map(
                start ->
                    found.get(start) == ReferenceRole.DECLARATION
                        ? "%s: %s at %s would then override the renamed method"
                            .formatted(
                                renaming,
                                elementAt(analysis, start)
                                    .map(RenameBindingCheck::describe)
                                    .orElse("a method"),
                                place(file, start))
                        : "%s: `%s` at %s would then refer to the renamed %s instead"
                            .formatted(
                                renaming,
                                renamed.simpleName(),
                                place(file, start),
                                RenameConflicts.kindName(renamed.kind())));
    final Stream<String> rebound =
        expected.stream()
            .filter(start -> !found.containsKey(start))
            .sorted()
            .flatMap(
                start ->
                    elementAt(analysis, start)
                        .filter(other -> named(other, renamed.simpleName()))
                        .stream()
                        .map(
                            other ->
                                "%s: the renamed use at %s would then refer to %s"
                                    .formatted(renaming, place(file, start), describe(other))));
    return Stream.concat(captured, rebound);
  }

  private static Map<Integer, ReferenceRole> uses(
      final AttributedFileAnalysis analysis, final ReferenceTarget renamed, final EditedFile file) {
    try {
      return ReferenceLocator.references(analysis, renamed, file.uri(), true).stream()
          .collect(
              Collectors.toMap(
                  match ->
                      SourceLocator.toOffset(
                          file.content(),
                          match.range().getStart().getLine(),
                          match.range().getStart().getCharacter()),
                  ReferenceMatch::role,
                  (first, second) -> first));
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot read the edited text of %s".formatted(file.uri()), e);
    }
  }

  // A renamed site that now resolves to another declaration of the new name means something else;
  // one that resolves to nothing named so (javac found only an enclosing tree) does not resolve at
  // all, a compile error the diagnostics report.
  private static boolean named(final Element element, final String name) {
    final Element named =
        element.getKind() == ElementKind.CONSTRUCTOR ? element.getEnclosingElement() : element;
    return named.getSimpleName().contentEquals(name);
  }

  private static Optional<Element> elementAt(
      final AttributedFileAnalysis analysis, final int offset) {
    return Optional.ofNullable(SourceLocator.pathAt(analysis.trees(), analysis.tree(), offset))
        .map(path -> SourceLocator.elementAt(analysis.trees(), path));
  }

  private static String place(final EditedFile file, final int offset) {
    final var position = SourceLocator.offsetToPosition(file.content(), offset);
    return "%s:%d".formatted(Path.of(URI.create(file.uri())).getFileName(), position.getLine() + 1);
  }

  private static String describe(final Element element) {
    final String kind = RenameConflicts.kindName(element.getKind());
    if (element instanceof final ExecutableElement method
        && method.getEnclosingElement() instanceof final TypeElement owner) {
      return "the %s `%s.%s(%s)`"
          .formatted(
              kind,
              owner.getSimpleName(),
              method.getSimpleName(),
              method.getParameters().stream()
                  .map(parameter -> parameter.asType().toString())
                  .collect(Collectors.joining(", ")));
    }

    return element.getEnclosingElement() instanceof final TypeElement owner
        ? "the %s `%s.%s`".formatted(kind, owner.getSimpleName(), element.getSimpleName())
        : "the %s `%s`".formatted(kind, element.getSimpleName());
  }
}
