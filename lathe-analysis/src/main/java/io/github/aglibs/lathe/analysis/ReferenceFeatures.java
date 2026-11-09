package io.github.aglibs.lathe.analysis;

import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.core.Stopwatch;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

// What a symbol at a cursor is as a search target (references, rename, instantiations, contracts,
// generated accessors), whether a rename would collide or change what a name means, and the
// references and incoming calls in one attributed file. Reads the session's cursor; keeps no
// state.
public final class ReferenceFeatures {

  private static final Logger LOG = Logger.getLogger(ReferenceFeatures.class.getName());

  private final SourceAnalysisSession session;
  private final JavaSourceCompiler compiler;

  ReferenceFeatures(final SourceAnalysisSession session, final JavaSourceCompiler compiler) {
    this.session = session;
    this.compiler = compiler;
  }

  // The accessors Lombok generated from the field at the cursor, as reference targets: their calls
  // use the field. None for anything else.
  public List<ReferenceTarget> generatedAccessorTargets(final SourceFeatureRequest request) {
    final Optional<Cursor> at = session.cursor(request);
    if (at.isEmpty()) {
      return List.of();
    }

    final Cursor cur = at.get();
    final Element element = cur.elementOrJavadocReference();
    if (element == null) {
      return List.of();
    }

    final var analysis = cur.analysis();
    return GeneratedCode.generatedAccessors(element).stream()
        .map(
            accessor ->
                ReferenceTarget.from(
                    accessor, analysis.trees(), analysis.types(), analysis.elements()))
        .toList();
  }

  public ReferenceTarget resolveTarget(final SourceFeatureRequest request) {
    final Optional<Cursor> at = session.cursor(request);
    if (at.isEmpty()) {
      return null;
    }

    final Cursor cur = at.get();
    final var trees = cur.analysis().trees();
    final var constructor = CursorElements.constructorAtNewClassType(trees, cur.path());
    final var element = constructor != null ? constructor : cur.elementOrJavadocReference();
    if (element == null) {
      return null;
    }

    return ReferenceTarget.from(element, trees, cur.analysis().types(), cur.analysis().elements());
  }

  // A constructor rename redirects to its enclosing type. Null when the cursor does not name a
  // renameable element (the file move a public top-level type needs is gated in WorkspaceSession).
  public ReferenceTarget resolveRenameTarget(final SourceFeatureRequest request) {
    final Optional<Cursor> at = session.cursor(request);
    if (at.isEmpty()) {
      return null;
    }

    final Cursor cur = at.get();
    final Element element = renameElement(request, cur);
    return element == null
        ? null
        : ReferenceTarget.from(
            element, cur.analysis().trees(), cur.analysis().types(), cur.analysis().elements());
  }

  public Optional<List<Location>> localRenameTextMentions(final SourceFeatureRequest request) {
    final Optional<Cursor> at = session.cursor(request);
    if (at.isEmpty()) {
      return Optional.empty();
    }

    final Cursor cur = at.get();
    final Element element = renameElement(request, cur);
    if (element == null) {
      return Optional.empty();
    }

    final var analysis = cur.analysis();
    final var trees = analysis.trees();
    final ReferenceTarget target =
        ReferenceTarget.from(element, trees, analysis.types(), analysis.elements());
    if (!target.isLocalScope()) {
      return Optional.empty();
    }

    final TreePath declaration = trees.getPath(element);
    for (TreePath scope = declaration == null ? null : declaration.getParentPath();
        scope != null;
        scope = scope.getParentPath()) {
      final Tree tree = scope.getLeaf();
      switch (tree.getKind()) {
        case METHOD,
            LAMBDA_EXPRESSION,
            BLOCK,
            FOR_LOOP,
            ENHANCED_FOR_LOOP,
            CATCH,
            TRY,
            CLASS,
            INTERFACE,
            ENUM,
            ANNOTATION_TYPE,
            RECORD -> {
          return Optional.of(
              TextMentions.literals(
                      trees, analysis.tree(), tree, element.getSimpleName().toString())
                  .stream()
                  .map(location -> new Location(request.uri(), location.getRange()))
                  .toList());
        }
        default -> {}
      }
    }

    return Optional.of(List.of());
  }

  // Why renaming the symbol at the cursor to newName would collide with an existing declaration.
  public Optional<String> renameConflict(final SourceFeatureRequest request, final String newName) {
    final Optional<Cursor> at = session.cursor(request);
    if (at.isEmpty()) {
      return Optional.empty();
    }

    final Cursor cur = at.get();
    final Element element = renameElement(request, cur);
    return element == null
        ? Optional.empty()
        : RenameConflicts.find(element, newName, cur.analysis());
  }

  // Whether renaming keeps what every name in this module's checked files means, attributing them
  // as the rename leaves them; the reason to refuse when it does not.
  public Optional<String> renameBindingConflict(
      final RenameBindingCheck.RenameCheck check,
      final List<RenameBindingCheck.CheckedFile> visibleEdits,
      final List<RenameBindingCheck.CheckedFile> checked,
      final CancelChecker cancelChecker) {
    final var t = Stopwatch.start();
    final Optional<String> conflict =
        RenameBindingCheck.find(compiler, check, visibleEdits, checked, cancelChecker);
    LOG.fine(
        () ->
            "[rename:check] %s edited=%d checked=%d %dms conflict=%s"
                .formatted(
                    check.newName(),
                    visibleEdits.size(),
                    checked.size(),
                    t.elapsedMs(),
                    conflict.isPresent()));
    return conflict;
  }

  private static Element renameElement(final SourceFeatureRequest request, final Cursor cur) {
    final Element constructor =
        CursorElements.constructorAtNewClassType(cur.analysis().trees(), cur.path());
    final Element named = constructor != null ? constructor : cur.elementOrJavadocReference();
    if (named == null) {
      return null;
    }

    final Element element =
        named.getKind() == ElementKind.CONSTRUCTOR ? named.getEnclosingElement() : named;
    return isRenameable(element) && cursorNames(request.content(), cur.offset(), element)
        ? element
        : null;
  }

  private static boolean isRenameable(final Element element) {
    return switch (element.getKind()) {
      case LOCAL_VARIABLE,
          PARAMETER,
          EXCEPTION_PARAMETER,
          RESOURCE_VARIABLE,
          BINDING_VARIABLE,
          TYPE_PARAMETER,
          FIELD,
          ENUM_CONSTANT,
          METHOD,
          RECORD_COMPONENT ->
          true;
      case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE -> true;
      default -> false;
    };
  }

  /**
   * The instantiation-search targets for the type at the cursor: one {@link ReferenceTarget} per
   * constructor (including the synthesized default), so a reference search yields the type's {@code
   * new XXX(...)} sites. Empty when the cursor is not on a type (or a constructor of one).
   */
  public List<ReferenceTarget> instantiationTargets(final SourceFeatureRequest request) {
    final Optional<Cursor> at = session.cursor(request);
    if (at.isEmpty()) {
      return List.of();
    }

    final Cursor cur = at.get();
    final var element = CursorElements.elementAt(cur.analysis().trees(), cur.path());
    final var type = instantiableTypeOf(element);
    if (type == null) {
      return List.of();
    }

    final var types = cur.analysis().types();
    final var elements = cur.analysis().elements();
    return ElementFilter.constructorsIn(type.getEnclosedElements()).stream()
        .map(constructor -> ReferenceTarget.from(constructor, types, elements))
        .toList();
  }

  private static TypeElement instantiableTypeOf(final Element element) {
    if (element instanceof final TypeElement type) {
      return type;
    }

    if (element != null && element.getKind() == ElementKind.CONSTRUCTOR) {
      return (TypeElement) element.getEnclosingElement();
    }

    return null;
  }

  public ReferenceTarget resolveContractTarget(final SourceFeatureRequest request) {
    final Optional<Cursor> at = session.cursor(request);
    if (at.isEmpty()) {
      return null;
    }

    final Cursor cur = at.get();
    final var element = CursorElements.elementAt(cur.analysis().trees(), cur.path());
    if (element == null) {
      return null;
    }

    return DeclarationLocator.findContract(
            element, cur.analysis().types(), cur.analysis().elements())
        .map(
            contract ->
                ReferenceTarget.from(contract, cur.analysis().types(), cur.analysis().elements()))
        .orElse(null);
  }

  // The references to target in one attributed file, the open file's cached analysis or a
  // transient one; none when it did not attribute. exposureFamily: the family whose exposure to
  // unnamed files to report (FamilyExposure), empty for none.
  public SearchHits<ReferenceMatch> references(
      final AttributedFileAnalysis analysis,
      final String uri,
      final ReferenceTarget target,
      final boolean includeDeclaration,
      final Set<String> exposureFamily) {
    return new SearchHits<>(
        locateReferences(uri, target, includeDeclaration, analysis),
        FamilyExposure.exposures(analysis, exposureFamily));
  }

  // The calls to the item's method in one attributed file, as references() reads it.
  public SearchHits<CallHierarchyIncomingCall> incomingCalls(
      final AttributedFileAnalysis analysis,
      final String uri,
      final CallHierarchyItemData item,
      final Set<String> exposureFamily) {
    return new SearchHits<>(
        locateIncomingCalls(uri, item, analysis),
        FamilyExposure.exposures(analysis, exposureFamily));
  }

  private List<CallHierarchyIncomingCall> locateIncomingCalls(
      final String uri, final CallHierarchyItemData item, final AttributedFileAnalysis analysis) {
    if (analysis == null || analysis.tree() == null) {
      return List.of();
    }
    try {
      return CallHierarchyIncomingLocator.scan(analysis, item, uri);
    } catch (final IOException e) {
      LOG.log(
          Level.WARNING, e, () -> "[incomingCalls] failed to read source for %s".formatted(uri));
      return List.of();
    }
  }

  private List<ReferenceMatch> locateReferences(
      final String uri,
      final ReferenceTarget target,
      final boolean includeDeclaration,
      final AttributedFileAnalysis analysis) {
    if (analysis == null || analysis.tree() == null) {
      return List.of();
    }

    try {
      final List<ReferenceMatch> results =
          ReferenceLocator.references(analysis, target, uri, includeDeclaration);
      LOG.fine(
          () ->
              "[references:file] uri=%s element=%s hits=%d"
                  .formatted(uri, target.simpleName(), results.size()));
      return results;
    } catch (final IOException e) {
      throw new UncheckedIOException("cannot read the source of %s".formatted(uri), e);
    }
  }

  // A rename edits the identifier at the cursor, so the target must be what that identifier names;
  // otherwise Javadoc prose, a comment, or a keyword inside a class body would rename the
  // enclosing class.
  private static boolean cursorNames(
      final String content, final long offset, final Element target) {
    final int start = DeclarationNames.identifierStart(content, (int) offset);
    final int end = DeclarationNames.identifierEnd(content, (int) offset);
    return target.getSimpleName().contentEquals(content.substring(start, end));
  }
}
