package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

// Whether a rename keeps what every name means. A rename adds one declaration of the new name and
// removes the old one, so a name means something else afterwards exactly when the uses of the
// renamed declaration, found again in the edited text, differ from the sites the rename edits: an
// extra one is an existing use the new name captures, a missing one a renamed site that now means
// something else. A renamed site that no longer resolves is a compile error the diagnostics report.
public final class RenameBindingCheck {

  private RenameBindingCheck() {}

  // A file as the rename leaves it: its text, with the rename's edits applied as the client applies
  // them, and the spans the edits replaced, to move the old text's offsets to the new.
  public record EditedFile(String uri, String content, List<Span> renamed, int newLength) {

    public EditedFile {
      ValidCheck.check().notBlank(uri, "uri").notNull(content, "content").validate();
      renamed = renamed.stream().sorted(Comparator.comparingInt(Span::start)).toList();
    }

    // The file with each renamed range replaced by the new name; a file the rename does not edit
    // keeps its text.
    public static EditedFile of(
        final String uri, final String content, final List<Range> ranges, final String newName) {
      final List<Span> renamed = ranges.stream().map(range -> span(content, range)).toList();
      final List<TextEdit> edits =
          ranges.stream().map(range -> new TextEdit(range, newName)).toList();
      return new EditedFile(
          uri, SourceLocator.applyEdits(content, edits), renamed, newName.length());
    }

    public boolean edited() {
      return !renamed.isEmpty();
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
  public record Span(int start, int length) {

    public Span {
      ValidCheck.check().assertTrue(start >= 0 && length >= 0, "span").validate();
    }
  }

  // The renamed declaration under its new name, a type's binary name included; a local keeps its
  // identity by where its declaration starts, which moves with the renamed sites before it.
  public static ReferenceTarget renamedTarget(
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
        target.hasDeclarationIdentity()
            ? declaringFile.shifted((int) target.declarationOffset())
            : target.declarationOffset());
  }

  // A binary name with its last segment, after the package's `.` or the outer type's `$`, renamed.
  private static String renamedBinaryName(final String binaryName, final String newName) {
    final int end = Math.max(binaryName.lastIndexOf('.'), binaryName.lastIndexOf('$'));
    return "%s%s".formatted(binaryName.substring(0, end + 1), newName);
  }

  // Attributes the files together, the edited ones as the rename leaves them, and checks the
  // checked ones: the first name whose meaning would change, as the reason to refuse.
  public static Optional<String> find(
      final JavaSourceCompiler compiler,
      final List<EditedFile> files,
      final Set<String> checkedUris,
      final ReferenceTarget renamed,
      final String oldName) {
    final Map<String, EditedFile> byUri =
        files.stream().collect(Collectors.toMap(EditedFile::uri, Function.identity()));
    return compiler.analyzeDetached(
        files.stream().map(EditedFile::source).toList(),
        analyses ->
            analyses.stream()
                .filter(analysis -> checkedUris.contains(analysis.uri()))
                .flatMap(
                    analysis ->
                        changedMeaning(analysis, byUri.get(analysis.uri()), renamed, oldName))
                .findFirst());
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
                    elementAt(analysis, start).stream()
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
