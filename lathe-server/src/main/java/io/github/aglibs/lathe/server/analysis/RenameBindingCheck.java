package io.github.aglibs.lathe.server.analysis;

import io.github.aglibs.validcheck.ValidCheck;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import org.eclipse.lsp4j.Range;

// Whether a rename keeps what every name means. A rename adds one declaration of the new name and
// removes the old one, so a name means something else afterwards exactly when the uses of the
// renamed declaration, found again in the edited text, differ from the sites the rename edits: an
// extra one is an existing use the new name captures, a missing one a renamed site that now means
// something else. A renamed site that no longer resolves is a compile error the diagnostics report.
public final class RenameBindingCheck {

  private RenameBindingCheck() {}

  // A file as the rename leaves it: its text, and where each renamed name starts before and after.
  public record EditedFile(String uri, String content, List<Integer> oldStarts, int delta) {

    public EditedFile {
      ValidCheck.check().notBlank(uri, "uri").notNull(content, "content").validate();
      oldStarts = oldStarts.stream().sorted().toList();
    }

    // The file with the renamed ranges replaced by the new name; a file the rename does not edit
    // keeps its text and has no renamed sites.
    public static EditedFile of(
        final String uri,
        final String content,
        final List<Range> renamed,
        final String oldName,
        final String newName) {
      final List<Integer> starts =
          renamed.stream()
              .map(
                  range ->
                      SourceLocator.toOffset(
                          content, range.getStart().getLine(), range.getStart().getCharacter()))
              .sorted()
              .toList();
      final var text = new StringBuilder(content);
      for (int i = starts.size() - 1; i >= 0; i--) {
        text.replace(starts.get(i), starts.get(i) + oldName.length(), newName);
      }

      return new EditedFile(uri, text.toString(), starts, newName.length() - oldName.length());
    }

    // Where an offset of the text before the rename lands after it.
    private int shifted(final int oldOffset) {
      return oldOffset
          + delta * (int) oldStarts.stream().filter(start -> start < oldOffset).count();
    }

    TransientSource source() {
      return new TransientSource(uri, content);
    }

    private Set<Integer> renamedStarts() {
      return IntStream.range(0, oldStarts.size())
          .mapToObj(i -> oldStarts.get(i) + i * delta)
          .collect(Collectors.toUnmodifiableSet());
    }
  }

  // The renamed declaration under its new name; a local keeps its identity by where its
  // declaration starts, which moves with the renamed sites before it.
  public static ReferenceTarget renamedTarget(
      final ReferenceTarget target, final String newName, final EditedFile declaringFile) {
    return new ReferenceTarget(
        target.kind(),
        target.qualifiedName(),
        newName,
        target.erasedDescriptor(),
        target.scope(),
        target.overriddenDeclarers(),
        target.overrideFamilyBounded(),
        target.hasDeclarationIdentity()
            ? declaringFile.shifted((int) target.declarationOffset())
            : target.declarationOffset());
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
