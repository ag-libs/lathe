package io.github.aglibs.lathe.analysis;

import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.Stopwatch;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SignatureHelp;

// What the code at a cursor is and where it is declared: hover, signature help, whether the cursor
// is on a name, definition, declaration, type definition, implementations, and the functional
// interface a lambda search widens to. Reads the session's attributed analysis; keeps no state.
public final class NavigationFeatures {

  private static final Logger LOG = Logger.getLogger(NavigationFeatures.class.getName());

  private final SourceAnalysisSession session;
  private final JavaSourceCompiler compiler;
  private final SourceParser parser;
  private final DefinitionLocator definitionLocator;
  private final JavadocLocator javadocLocator;
  private final TypeHierarchyResolver typeHierarchyResolver;

  NavigationFeatures(
      final SourceAnalysisSession session,
      final JavaSourceCompiler compiler,
      final SourceParser parser,
      final DefinitionLocator definitionLocator,
      final JavadocLocator javadocLocator,
      final TypeHierarchyResolver typeHierarchyResolver) {
    this.session = session;
    this.compiler = compiler;
    this.parser = parser;
    this.definitionLocator = definitionLocator;
    this.javadocLocator = javadocLocator;
    this.typeHierarchyResolver = typeHierarchyResolver;
  }

  public SignatureHelp signatureHelp(final SourceFeatureRequest request) {
    final Optional<Cursor> at = session.cursor(request);
    if (at.isEmpty()) {
      return null;
    }

    final Cursor cur = at.get();
    final long offset =
        SourceLocator.toOffset(
            cur.analysis().tree(), request.pos().getLine(), request.pos().getCharacter());
    return SignatureHelpResolver.resolve(
        cur.analysis(), cur.path(), offset, parser, request.allSourceRoots(), javadocLocator);
  }

  // Whether the request's position is on a name, not on a word of a comment or a literal; true
  // when the file has no analysis to check against.
  public boolean onName(final SourceFeatureRequest request) {
    return session
        .cursor(request)
        .map(
            cur ->
                IOUtil.unchecked(
                    () -> SourceLocator.onName(cur.analysis(), cur.path(), cur.offset())))
        .orElse(true);
  }

  public Hover hover(final SourceFeatureRequest request) {
    final var t = Stopwatch.start();
    final Optional<Cursor> at = session.cursor(request);
    if (at.isEmpty()) {
      return null;
    }

    final Cursor cur = at.get();
    final VariableElement param =
        SourceLocator.parameterElementAt(cur.analysis().trees(), cur.path());
    if (param != null) {
      final var callee = (ExecutableElement) param.getEnclosingElement();
      final int index = callee.getParameters().indexOf(param);
      final List<String> paramNames = parser.resolveParamNames(callee, request.allSourceRoots());
      final var fmt = new TypeDisplayFormatter(cur.analysis().types());
      final String label =
          HoverFormatter.formatParam(
              param,
              fmt,
              paramNames,
              index,
              callee.isVarArgs() && index == callee.getParameters().size() - 1);
      LOG.fine(() -> "[hover] param=%s %dms".formatted(param, t.elapsedMs()));
      return new Hover(new MarkupContent("markdown", "```java\n%s\n```".formatted(label)));
    }

    final Element element = cur.elementOrJavadocReference();
    final TypeMirror type =
        cur.path() != null ? cur.analysis().trees().getTypeMirror(cur.path()) : null;
    final List<Path> allRoots = request.allSourceRoots();
    final var javadoc =
        javadocLocator
            .locate(element, cur.analysis().trees(), allRoots)
            .map(JavadocMarkdownPrinter::format)
            .orElse(null);
    final var origin =
        request.libraries().originLabel(element, compiler.fileManager()).orElse(null);
    final var fmt = new TypeDisplayFormatter(cur.analysis().types());
    final List<String> sourceParamNames =
        element instanceof ExecutableElement exe ? parser.resolveParamNames(exe, allRoots) : null;
    LOG.fine(
        () ->
            "[hover] %dms element=%s type=%s doc=%s origin=%s"
                .formatted(t.elapsedMs(), element, type, javadoc != null, origin));
    return HoverFormatter.format(element, type, javadoc, origin, fmt, sourceParamNames)
        .map(md -> new Hover(new MarkupContent("markdown", md)))
        .orElse(null);
  }

  // The simple name of the functional interface under the cursor, or whose single abstract method
  // is; empty otherwise. Lets the workspace widen an implementation search to the lambdas and
  // method references that implement it.
  public Optional<String> functionalInterfaceName(final SourceFeatureRequest request) {
    final Optional<Cursor> at = session.cursor(request);
    if (at.isEmpty()) {
      return Optional.empty();
    }

    final Cursor cur = at.get();
    final var element = SourceLocator.elementAt(cur.analysis().trees(), cur.path());
    if (element == null) {
      return Optional.empty();
    }

    final var owner =
        element instanceof final TypeElement type ? type : element.getEnclosingElement();
    if (!(owner instanceof final TypeElement ownerType)
        || ownerType.getKind() != ElementKind.INTERFACE) {
      return Optional.empty();
    }

    final var sam = FunctionalInterfaces.singleAbstractMethod(ownerType, cur.analysis().elements());
    return sam != null && (element == ownerType || sam.equals(element))
        ? Optional.of(ownerType.getSimpleName().toString())
        : Optional.empty();
  }

  private Optional<Location> findDefinitionLocation(
      final SourceFeatureRequest request, final Cursor cur, final Element element) {
    Optional<Location> result =
        definitionLocator.locate(
            element, cur.analysis().trees(), request.sourceRoots(), request.uri());
    if (result.isEmpty()) {
      result =
          request
              .libraries()
              .externalSourceRoot(element, compiler.fileManager())
              .flatMap(root -> DefinitionLocator.findSourceFile(element, List.of(root)))
              .flatMap(
                  file ->
                      definitionLocator
                          .parsePosition(file, element)
                          .map(
                              lspPos ->
                                  new Location(
                                      file.toUri().toString(), new Range(lspPos, lspPos))));
    }

    return result;
  }

  public Optional<Location> definition(final SourceFeatureRequest request) {
    final var t = Stopwatch.start();
    final Optional<Cursor> at = session.cursor(request);
    if (at.isEmpty()) {
      return Optional.empty();
    }

    final Cursor cur = at.get();
    final var element = cur.elementOrJavadocReference();
    final var result = findDefinitionLocation(request, cur, element);

    LOG.fine(
        () ->
            "[definition] %dms element=%s → %s"
                .formatted(
                    t.elapsedMs(),
                    element,
                    result.map(NavigationFeatures::where).orElse("not found")));
    return result;
  }

  // The declaration of the type of the symbol at the cursor: a variable's or field's type, a
  // method's return type, a constructor's class, a type itself; an array's element type, a
  // generic's
  // declared type. Nothing for a primitive or void.
  public Optional<Location> typeDefinition(final SourceFeatureRequest request) {
    final var t = Stopwatch.start();
    final Optional<Cursor> at = session.cursor(request);
    if (at.isEmpty()) {
      return Optional.empty();
    }

    final Cursor cur = at.get();
    final var element = cur.elementOrJavadocReference();
    final Optional<Location> result =
        typeOf(element, cur.analysis().types())
            .flatMap(type -> findDefinitionLocation(request, cur, type));
    LOG.fine(
        () ->
            "[typeDefinition] %dms element=%s → %s"
                .formatted(
                    t.elapsedMs(),
                    element,
                    result.map(NavigationFeatures::where).orElse("not found")));
    return result;
  }

  private static Optional<Element> typeOf(final Element element, final Types types) {
    if (element == null || element instanceof TypeElement) {
      return Optional.ofNullable(element);
    }

    TypeMirror type =
        element instanceof final ExecutableElement method
            ? method.getKind() == ElementKind.CONSTRUCTOR
                ? method.getEnclosingElement().asType()
                : method.getReturnType()
            : element.asType();
    while (type instanceof final ArrayType array) {
      type = array.getComponentType();
    }

    return Optional.ofNullable(types.asElement(type));
  }

  public Optional<Location> declaration(final SourceFeatureRequest request) {
    final var t = Stopwatch.start();
    final Optional<Cursor> at = session.cursor(request);
    if (at.isEmpty()) {
      return Optional.empty();
    }

    final Cursor cur = at.get();
    final var element = SourceLocator.elementAt(cur.analysis().trees(), cur.path());
    if (element != null) {
      final var contractOpt =
          DeclarationLocator.findContract(
              element, cur.analysis().types(), cur.analysis().elements());
      if (contractOpt.isPresent()) {
        final var contract = contractOpt.get();
        final var result = findDefinitionLocation(request, cur, contract);

        LOG.fine(
            () ->
                "[declaration] %dms element=%s contract=%s → %s"
                    .formatted(
                        t.elapsedMs(),
                        element,
                        contract,
                        result.map(NavigationFeatures::where).orElse("not found")));
        return result;
      }
    }

    final var fallback = findDefinitionLocation(request, cur, element);

    LOG.fine(
        () ->
            "[declaration] %dms fallback to definition %s → %s"
                .formatted(
                    t.elapsedMs(),
                    element,
                    fallback.map(NavigationFeatures::where).orElse("not found")));
    return fallback;
  }

  public List<Location> typeImplementations(
      final SourceFeatureRequest request, final WorkspaceTypeIndex typeIndex) {
    final var t = Stopwatch.start();
    final Optional<Cursor> at = session.cursor(request);
    if (at.isEmpty()) {
      return List.of();
    }

    final Cursor cur = at.get();
    final var element = SourceLocator.elementAt(cur.analysis().trees(), cur.path());
    if (!(element instanceof final TypeElement typeElement)) {
      return List.of();
    }

    final var binaryName = cur.analysis().elements().getBinaryName(typeElement).toString();
    final List<Path> sourceRoots = request.typeSourceRoots();
    final List<Location> results =
        typeIndex.transitiveSubtypes(binaryName).stream()
            .flatMap(entry -> typeHierarchyResolver.locateSource(entry, sourceRoots).stream())
            .toList();
    LOG.fine(
        () ->
            "[implementation:type] %s %dms hits=%d"
                .formatted(request.uri(), t.elapsedMs(), results.size()));
    return results;
  }

  private static String where(final Location location) {
    return "%s:%d".formatted(location.getUri(), location.getRange().getStart().getLine());
  }
}
