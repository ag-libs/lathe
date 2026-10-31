package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

// Extract method, expression form: the selected expression becomes a private method returning it,
// placed after the member holding it, and the expression a call to it; a second action also
// replaces the expression's exact copies in the class. A call recomputes the value where the
// expression stood, so the rewrite keeps behaviour whatever the expression does.
final class ExtractMethodProvider {

  private static final Logger LOG = Logger.getLogger(ExtractMethodProvider.class.getName());
  private static final String BASE_NAME = "extracted";

  List<Either<Command, CodeAction>> provide(
      final String uri, final Range range, final AttributedFileAnalysis analysis) {
    final CompilationUnitTree cu = analysis.tree();
    if (cu == null) {
      return List.of();
    }

    final Trees trees = analysis.trees();
    final TreePath exprPath = ExtractionSupport.selectedExpression(trees, cu, range);
    final TreePath member = exprPath != null ? enclosingMember(exprPath) : null;
    if (member == null || inInterface(member) || !replaceableByCall(exprPath, trees)) {
      return List.of();
    }

    final TypeMirror returnType = trees.getTypeMirror(exprPath);
    final Optional<Reads> reads = reads(exprPath, trees);
    if (returnType == null || reads.isEmpty()) {
      return List.of();
    }

    final List<TypeMirror> signatureTypes =
        Stream.concat(Stream.of(returnType), reads.get().types()).toList();
    if (!signatureTypes.stream().allMatch(CodeActionSupport::isDenotable)
        || namesTypeVariableOf(reads.get(), signatureTypes, ExecutableElement.class)) {
      return List.of();
    }

    final Optional<String> source = CodeActionSupport.source(cu);
    if (source.isEmpty()) {
      return List.of();
    }

    final var extraction =
        new Extraction(
            analysis,
            source.get(),
            member,
            exprPath,
            methodName(member.getParentPath(), analysis),
            returnType,
            reads.get().locals(),
            UncaughtExceptions.of(List.of(exprPath), analysis));
    // A static method cannot use the class's type variables.
    final boolean classTypeVariables =
        namesTypeVariableOf(reads.get(), signatureTypes, TypeElement.class);
    final List<TreePath> copies = copies(member.getParentPath(), exprPath, trees);
    final var actions = new ArrayList<Either<Command, CodeAction>>();
    final boolean selectionStatic = isStaticContext(exprPath, trees);
    if (!(selectionStatic && classTypeVariables)) {
      actions.add(
          extraction.action(
              uri,
              "Extract method '%s'".formatted(extraction.name()),
              List.of(exprPath),
              selectionStatic));
    }

    final boolean copiesStatic = copies.stream().anyMatch(copy -> isStaticContext(copy, trees));
    if (copies.size() > 1 && !(copiesStatic && classTypeVariables)) {
      actions.add(
          extraction.action(
              uri,
              "Extract method '%s' (replace all %d occurrences)"
                  .formatted(extraction.name(), copies.size()),
              copies,
              copiesStatic));
    }

    LOG.fine(
        () ->
            "[codeAction:extractMethod] %s actions=%d"
                .formatted(extraction.name(), actions.size()));
    return actions;
  }

  // What the expression reads: the locals it does not declare itself, in order of first use (the
  // new method's parameters), and the type variables it names.
  private record Reads(List<VariableElement> locals, Set<TypeParameterElement> typeVariables) {

    Reads {
      ValidCheck.check()
          .notNull(locals, "locals")
          .notNull(typeVariables, "typeVariables")
          .validate();
      locals = List.copyOf(locals);
      typeVariables = Set.copyOf(typeVariables);
    }

    Stream<TypeMirror> types() {
      return locals.stream().map(Element::asType);
    }
  }

  // Empty when the expression cannot move into a method: it writes a local declared outside it, or
  // names a local class (invisible from a new member).
  private static Optional<Reads> reads(final TreePath exprPath, final Trees trees) {
    final var declaredInside = new HashSet<Element>();
    final var locals = new LinkedHashSet<VariableElement>();
    final var typeVariables = new HashSet<TypeParameterElement>();
    final var movable = new AtomicBoolean(true);
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitVariable(final VariableTree tree, final Void unused) {
        declaredInside.add(trees.getElement(getCurrentPath()));
        return super.visitVariable(tree, unused);
      }

      @Override
      public Void visitIdentifier(final IdentifierTree tree, final Void unused) {
        final Element element = trees.getElement(getCurrentPath());
        if (element instanceof final VariableElement variable
            && ExtractionSupport.isLocalVariable(variable)
            && !declaredInside.contains(variable)) {
          if (ExtractionSupport.isAssignmentTarget(getCurrentPath())) {
            movable.set(false);
          }

          locals.add(variable);
        }

        if (element instanceof final TypeParameterElement typeVariable) {
          typeVariables.add(typeVariable);
        }

        if (element instanceof final TypeElement type
            && type.getNestingKind() == NestingKind.LOCAL) {
          movable.set(false);
        }

        return super.visitIdentifier(tree, unused);
      }
    }.scan(exprPath, null);
    return movable.get()
        ? Optional.of(new Reads(List.copyOf(locals), typeVariables))
        : Optional.empty();
  }

  // The new method and the edits that call it from a set of sites.
  private record Extraction(
      AttributedFileAnalysis analysis,
      String source,
      TreePath member,
      TreePath expression,
      String name,
      TypeMirror returnType,
      List<VariableElement> parameters,
      List<TypeMirror> thrown) {

    Extraction {
      ValidCheck.check()
          .notNull(analysis, "analysis")
          .notNull(source, "source")
          .notNull(member, "member")
          .notNull(expression, "expression")
          .notNull(name, "name")
          .notNull(returnType, "returnType")
          .notNull(parameters, "parameters")
          .notNull(thrown, "thrown")
          .validate();
      parameters = List.copyOf(parameters);
      thrown = List.copyOf(thrown);
    }

    Either<Command, CodeAction> action(
        final String uri, final String title, final List<TreePath> sites, final boolean isStatic) {
      return ExtractionSupport.action(
          uri, title, ExtractionSupport.METHOD_KIND, edits(sites, isStatic));
    }

    private List<TextEdit> edits(final List<TreePath> sites, final boolean isStatic) {
      final CompilationUnitTree cu = analysis.tree();
      final long memberEnd = end(member);
      final String call =
          "%s(%s)"
              .formatted(
                  name,
                  parameters.stream()
                      .map(parameter -> parameter.getSimpleName().toString())
                      .collect(Collectors.joining(", ")));
      return Stream.of(
              Stream.of(
                  ExtractionSupport.replaceEdit(cu, memberEnd, memberEnd, methodText(isStatic))),
              sites.stream()
                  .map(site -> ExtractionSupport.replaceEdit(cu, start(site), end(site), call)),
              CodeActionSupport.importEditsFor(
                  analysis,
                  Stream.of(
                          Stream.of(returnType),
                          parameters.stream().map(Element::asType),
                          thrown.stream())
                      .flatMap(types -> types))
                  .stream())
          .flatMap(edits -> edits)
          .toList();
    }

    private String methodText(final boolean isStatic) {
      final var format = new TypeDisplayFormatter(analysis.types());
      final String indent = CodeActionSupport.lineIndent(source, (int) start(member));
      final String step =
          CodeActionSupport.indentStep(
              indent, CodeActionSupport.lineIndent(source, (int) start(member.getParentPath())));
      final String modifier = finalParameters() ? "final " : "";
      final String parameterList =
          parameters.stream()
              .map(
                  parameter ->
                      "%s%s %s"
                          .formatted(
                              modifier,
                              format.format(parameter.asType()),
                              parameter.getSimpleName()))
              .collect(Collectors.joining(", "));
      final String throwsClause =
          thrown.isEmpty()
              ? ""
              : " throws " + thrown.stream().map(format::format).collect(Collectors.joining(", "));
      return "\n\n%sprivate %s%s %s(%s)%s {\n%s%sreturn %s;\n%s}"
          .formatted(
              indent,
              isStatic ? "static " : "",
              format.format(returnType),
              name,
              parameterList,
              throwsClause,
              indent,
              step,
              source.substring((int) start(expression), (int) end(expression)),
              indent);
    }

    // The file's style: parameters are final when every parameter of the enclosing method is.
    private boolean finalParameters() {
      return member.getLeaf() instanceof final MethodTree method
          && !method.getParameters().isEmpty()
          && method.getParameters().stream()
              .allMatch(parameter -> parameter.getModifiers().getFlags().contains(Modifier.FINAL));
    }

    private long start(final TreePath path) {
      return analysis
          .trees()
          .getSourcePositions()
          .getStartPosition(analysis.tree(), path.getLeaf());
    }

    private long end(final TreePath path) {
      return analysis.trees().getSourcePositions().getEndPosition(analysis.tree(), path.getLeaf());
    }
  }

  // The class member holding the path: a method, constructor, initializer block or field.
  private static TreePath enclosingMember(final TreePath path) {
    return Stream.iterate(path, p -> p.getParentPath() != null, TreePath::getParentPath)
        .filter(p -> p.getParentPath().getLeaf() instanceof ClassTree)
        .findFirst()
        .filter(
            p ->
                p.getLeaf() instanceof MethodTree
                    || p.getLeaf() instanceof BlockTree
                    || p.getLeaf() instanceof VariableTree)
        .orElse(null);
  }

  // A private interface method needs Java 9+, and the project's release is not known here.
  private static boolean inInterface(final TreePath member) {
    final Tree.Kind kind = member.getParentPath().getLeaf().getKind();
    return kind == Tree.Kind.INTERFACE || kind == Tree.Kind.ANNOTATION_TYPE;
  }

  // Whether a call can stand where the expression is: not a compile-time constant (a case label or
  // an annotation value needs one, and extracting one gains nothing), not inside a case label or an
  // annotation, not an assignment's target.
  private static boolean replaceableByCall(final TreePath exprPath, final Trees trees) {
    return !ExtractionSupport.isCompileTimeConstant(exprPath, trees)
        && !ExtractionSupport.isAssignmentTarget(exprPath)
        && Stream.iterate(exprPath, Objects::nonNull, TreePath::getParentPath)
            .noneMatch(path -> path.getLeaf() instanceof AnnotationTree || isCaseLabel(path));
  }

  private static boolean isCaseLabel(final TreePath path) {
    return path.getParentPath() != null
        && path.getParentPath().getLeaf() instanceof final CaseTree caseTree
        && caseTree.getExpressions().contains(path.getLeaf());
  }

  // The selection and its exact copies among the class's own members: the same shape reading the
  // same variables, so each copy's inputs are in scope there.
  private static List<TreePath> copies(
      final TreePath classPath, final TreePath exprPath, final Trees trees) {
    return ExtractionSupport.occurrences(classPath, exprPath, trees).stream()
        .filter(copy -> CodeActionSupport.enclosingClass(copy).getLeaf() == classPath.getLeaf())
        .toList();
  }

  // Code runs without an instance in a static member, or in a this(...)/super(...) argument.
  private static boolean isStaticContext(final TreePath path, final Trees trees) {
    final TreePath member = enclosingMember(path);
    final boolean staticMember =
        member.getLeaf() instanceof final BlockTree block
            ? block.isStatic()
            : trees.getElement(member).getModifiers().contains(Modifier.STATIC);
    return staticMember
        || Stream.iterate(path, p -> p != member, TreePath::getParentPath)
            .anyMatch(p -> ExtractionSupport.isConstructorCall(p, trees));
  }

  private static boolean namesTypeVariableOf(
      final Reads reads,
      final List<TypeMirror> signatureTypes,
      final Class<? extends Element> owner) {
    return reads.typeVariables().stream()
            .anyMatch(variable -> owner.isInstance(variable.getGenericElement()))
        || signatureTypes.stream().anyMatch(type -> mentionsTypeVariableOf(type, owner));
  }

  private static boolean mentionsTypeVariableOf(
      final TypeMirror type, final Class<? extends Element> owner) {
    if (type instanceof final TypeVariable variable) {
      return owner.isInstance(((TypeParameterElement) variable.asElement()).getGenericElement());
    }

    if (type instanceof final DeclaredType declared) {
      return declared.getTypeArguments().stream()
          .anyMatch(argument -> mentionsTypeVariableOf(argument, owner));
    }

    if (type instanceof final ArrayType array) {
      return mentionsTypeVariableOf(array.getComponentType(), owner);
    }

    return type instanceof final WildcardType wildcard
        && Stream.of(wildcard.getExtendsBound(), wildcard.getSuperBound())
            .filter(Objects::nonNull)
            .anyMatch(bound -> mentionsTypeVariableOf(bound, owner));
  }

  // `extracted`, or the first `extractedN` no method of the class declares or inherits: a new
  // overload of an existing name could change which method the class's own calls pick.
  private static String methodName(
      final TreePath classPath, final AttributedFileAnalysis analysis) {
    final Set<String> taken =
        analysis
            .elements()
            .getAllMembers((TypeElement) analysis.trees().getElement(classPath))
            .stream()
            .filter(element -> element.getKind() == ElementKind.METHOD)
            .map(element -> element.getSimpleName().toString())
            .collect(Collectors.toUnmodifiableSet());
    return VariableNameSuggester.uniquify(BASE_NAME, taken);
  }
}
