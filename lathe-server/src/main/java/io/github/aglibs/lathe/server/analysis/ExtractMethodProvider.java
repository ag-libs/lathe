package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

// Extract method: the selected expression, or run of statements, becomes a private method placed
// after the member holding it, and the selection a call to it. An expression's method returns its
// value, and a second action also replaces its exact copies in the class. Statements return the
// one local they assign or declare that is used afterwards (`T x = extracted(…);` or
// `x = extracted(…);`), or nothing. RegionFlow decides the parameters and refuses what would not
// keep behaviour.
final class ExtractMethodProvider {

  private static final Logger LOG = Logger.getLogger(ExtractMethodProvider.class.getName());
  private static final String BASE_NAME = "extracted";

  List<Either<Command, CodeAction>> provide(
      final String uri, final Range range, final AttributedFileAnalysis analysis) {
    final CompilationUnitTree cu = analysis.tree();
    if (cu == null) {
      return List.of();
    }

    // Whole statements first; a run the statement form refuses may still offer its value as an
    // expression. A lone declaration or assignment extracts the value it stores, which makes the
    // plainer method: `return a.trim();` rather than `t = a.trim(); return t;`.
    final List<TreePath> statements =
        ExtractionSupport.selectedStatements(analysis.trees(), cu, range);
    final TreePath stored = statements.size() == 1 ? storedValue(statements.get(0)) : null;
    final List<Either<Command, CodeAction>> fromStatements =
        statements.isEmpty() || stored != null ? List.of() : extract(uri, analysis, statements);
    if (!fromStatements.isEmpty()) {
      return fromStatements;
    }

    final TreePath expression =
        stored != null ? stored : ExtractionSupport.selectedExpression(analysis.trees(), cu, range);
    return expression != null && replaceableByCall(expression, analysis.trees())
        ? extract(uri, analysis, List.of(expression))
        : List.of();
  }

  // The actions for a region: one expression, or consecutive statements of one block.
  private static List<Either<Command, CodeAction>> extract(
      final String uri, final AttributedFileAnalysis analysis, final List<TreePath> region) {
    final Trees trees = analysis.trees();
    final TreePath member = enclosingMember(region.get(0));
    if (member == null || inInterface(member)) {
      return List.of();
    }

    final boolean expressionForm = region.get(0).getLeaf() instanceof ExpressionTree;
    final RegionFlow flow = RegionFlow.of(member, region, trees);
    final TypeMirror returnType;
    if (expressionForm) {
      returnType = trees.getTypeMirror(region.get(0));
    } else if (flow.returnsValue()) {
      returnType = ((ExecutableElement) trees.getElement(member)).getReturnType();
    } else {
      returnType =
          flow.outputs().stream()
              .findFirst()
              .map(Element::asType)
              .orElse(analysis.types().getNoType(TypeKind.VOID));
    }

    // A void expression is refused below as a type that cannot be written.
    final List<TypeMirror> signatureTypes =
        Stream.of(
                expressionForm || returnType.getKind() != TypeKind.VOID
                    ? Stream.of(returnType)
                    : Stream.<TypeMirror>empty(),
                flow.parameters().stream().map(Element::asType),
                flow.declaredHere().stream().map(Element::asType))
            .flatMap(types -> types)
            .toList();
    final List<String> refusals =
        Stream.of(
                flow.refusals().stream(),
                flow.outputs().size() > (expressionForm || flow.returnsValue() ? 0 : 1)
                    ? Stream.of("values flow out")
                    : Stream.<String>empty(),
                signatureTypes.stream().allMatch(CodeActionSupport::isDenotable)
                    ? Stream.<String>empty()
                    : Stream.of("a type cannot be written"))
            .flatMap(reasons -> reasons)
            .toList();
    final Optional<String> source = CodeActionSupport.source(analysis.tree());
    if (!refusals.isEmpty() || source.isEmpty()) {
      LOG.fine(() -> "[codeAction:extractMethod] refused: %s".formatted(refusals));
      return List.of();
    }

    final Set<TypeParameterElement> typeVariables =
        Stream.concat(
                flow.typeVariables().stream(),
                signatureTypes.stream().flatMap(ExtractMethodProvider::typeVariablesIn))
            .collect(Collectors.toUnmodifiableSet());
    final var extraction =
        new Extraction(
            analysis,
            source.get(),
            member,
            region,
            methodName(member.getParentPath(), analysis),
            methodTypeParameters(member, typeVariables, trees),
            returnType,
            flow.parameters(),
            flow.declaredHere(),
            flow.outputs(),
            flow.returnsValue(),
            UncaughtExceptions.of(region, analysis));
    // A static method cannot use the class's type variables.
    final boolean classTypeVariables =
        typeVariables.stream()
            .anyMatch(variable -> variable.getGenericElement() instanceof TypeElement);
    final var actions = new ArrayList<Either<Command, CodeAction>>();
    final boolean regionStatic = isStaticContext(region.get(0), trees);
    if (!(regionStatic && classTypeVariables)) {
      actions.add(
          extraction.action(
              uri,
              "Extract method '%s'".formatted(extraction.name()),
              List.of(region),
              regionStatic));
    }

    final List<TreePath> copies =
        expressionForm ? copies(member.getParentPath(), region.get(0), trees) : List.of();
    final boolean copiesStatic = copies.stream().anyMatch(copy -> isStaticContext(copy, trees));
    if (copies.size() > 1 && !(copiesStatic && classTypeVariables)) {
      actions.add(
          extraction.action(
              uri,
              "Extract method '%s' (replace all %d occurrences)"
                  .formatted(extraction.name(), copies.size()),
              copies.stream().map(List::of).toList(),
              copiesStatic));
    }

    LOG.fine(
        () ->
            "[codeAction:extractMethod] %s actions=%d"
                .formatted(extraction.name(), actions.size()));
    return actions;
  }

  // The new method, and the edits that call it from runs of trees: the region itself, or the
  // copies of an expression.
  private record Extraction(
      AttributedFileAnalysis analysis,
      String source,
      TreePath member,
      List<TreePath> region,
      String name,
      List<TypeParameterTree> typeParameters,
      TypeMirror returnType,
      List<VariableElement> parameters,
      List<VariableElement> declaredHere,
      List<VariableElement> outputs,
      boolean returnsValue,
      List<TypeMirror> thrown) {

    Extraction {
      ValidCheck.check()
          .notNull(analysis, "analysis")
          .notNull(source, "source")
          .notNull(member, "member")
          .notNull(region, "region")
          .notNull(name, "name")
          .notNull(typeParameters, "typeParameters")
          .notNull(returnType, "returnType")
          .notNull(parameters, "parameters")
          .notNull(declaredHere, "declaredHere")
          .notNull(outputs, "outputs")
          .notNull(thrown, "thrown")
          .validate();
      region = List.copyOf(region);
      typeParameters = List.copyOf(typeParameters);
      parameters = List.copyOf(parameters);
      declaredHere = List.copyOf(declaredHere);
      outputs = List.copyOf(outputs);
      thrown = List.copyOf(thrown);
    }

    Either<Command, CodeAction> action(
        final String uri,
        final String title,
        final List<List<TreePath>> sites,
        final boolean isStatic) {
      return ExtractionSupport.action(
          uri, title, ExtractionSupport.METHOD_KIND, edits(sites, isStatic));
    }

    private boolean expressionForm() {
      return region.get(0).getLeaf() instanceof ExpressionTree;
    }

    private List<TextEdit> edits(final List<List<TreePath>> sites, final boolean isStatic) {
      final CompilationUnitTree cu = analysis.tree();
      final long memberEnd = end(member);
      final String call =
          "%s%s(%s)%s"
              .formatted(
                  returnsValue
                      ? "return "
                      : outputs.stream().map(this::receiving).findFirst().orElse(""),
                  name,
                  parameters.stream()
                      .map(parameter -> parameter.getSimpleName().toString())
                      .collect(Collectors.joining(", ")),
                  expressionForm() ? "" : ";");
      return Stream.of(
              Stream.of(
                  ExtractionSupport.replaceEdit(cu, memberEnd, memberEnd, methodText(isStatic))),
              sites.stream()
                  .map(
                      site ->
                          ExtractionSupport.replaceEdit(
                              cu, start(site.get(0)), end(site.get(site.size() - 1)), call)),
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

    // Where the call's value lands: the declaration the region made, keeping its `final`, or an
    // earlier local.
    private String receiving(final VariableElement output) {
      return region.stream()
          .map(TreePath::getLeaf)
          .filter(VariableTree.class::isInstance)
          .map(VariableTree.class::cast)
          .filter(declaration -> declaration.getName().equals(output.getSimpleName()))
          .findFirst()
          .map(
              declaration ->
                  "%s%s %s = "
                      .formatted(
                          declaration.getModifiers().getFlags().contains(Modifier.FINAL)
                              ? "final "
                              : "",
                          new TypeDisplayFormatter(analysis.types()).format(output.asType()),
                          output.getSimpleName()))
          .orElse("%s = ".formatted(output.getSimpleName()));
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
      final String text =
          source.substring((int) start(region.get(0)), (int) end(region.get(region.size() - 1)));
      final String bodyIndent = indent + step;
      final String body =
          expressionForm()
              ? "%sreturn %s;".formatted(bodyIndent, text)
              : Stream.of(
                      declaredHere.stream()
                          .map(
                              local ->
                                  "%s%s %s;"
                                      .formatted(
                                          bodyIndent,
                                          format.format(local.asType()),
                                          local.getSimpleName())),
                      Stream.of(
                          CodeActionSupport.rebased(
                              text,
                              CodeActionSupport.lineIndent(source, (int) start(region.get(0))),
                              bodyIndent)),
                      outputs.stream()
                          .map(
                              output ->
                                  "%sreturn %s;".formatted(bodyIndent, output.getSimpleName())))
                  .flatMap(lines -> lines)
                  .collect(Collectors.joining("\n"));
      return "\n\n%sprivate %s%s%s %s(%s)%s {\n%s\n%s}"
          .formatted(
              indent,
              isStatic ? "static " : "",
              typeParameters.isEmpty()
                  ? ""
                  : "<%s> "
                      .formatted(
                          typeParameters.stream()
                              .map(
                                  parameter ->
                                      source.substring(
                                          (int) start(new TreePath(member, parameter)),
                                          (int) end(new TreePath(member, parameter))))
                              .collect(Collectors.joining(", "))),
              format.format(returnType),
              name,
              parameterList,
              throwsClause,
              body,
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

  private static TreePath storedValue(final TreePath statement) {
    if (statement.getLeaf() instanceof final VariableTree declaration
        && declaration.getInitializer() != null) {
      return new TreePath(statement, declaration.getInitializer());
    }

    if (statement.getLeaf() instanceof final ExpressionStatementTree expression
        && expression.getExpression() instanceof final AssignmentTree assignment) {
      return new TreePath(new TreePath(statement, assignment), assignment.getExpression());
    }

    return null;
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

  // The enclosing method's type parameters the new method needs: those named in the region or its
  // signature, and those their bounds name.
  private static List<TypeParameterTree> methodTypeParameters(
      final TreePath member, final Set<TypeParameterElement> named, final Trees trees) {
    if (!(member.getLeaf() instanceof final MethodTree method)) {
      return List.of();
    }

    final Set<TypeParameterElement> needed = new HashSet<>();
    final Deque<TypeParameterElement> pending = new ArrayDeque<>(named);
    while (!pending.isEmpty()) {
      final TypeParameterElement variable = pending.pop();
      if (needed.add(variable)) {
        variable.getBounds().stream()
            .flatMap(ExtractMethodProvider::typeVariablesIn)
            .forEach(pending::push);
      }
    }

    return method.getTypeParameters().stream()
        .filter(parameter -> needed.contains(trees.getElement(new TreePath(member, parameter))))
        .collect(Collectors.toUnmodifiableList());
  }

  // The type variables a type names: itself, or in its generic arguments, array component or
  // wildcard bounds.
  private static Stream<TypeParameterElement> typeVariablesIn(final TypeMirror type) {
    if (type instanceof final TypeVariable variable) {
      return Stream.of((TypeParameterElement) variable.asElement());
    }

    if (type instanceof final DeclaredType declared) {
      return declared.getTypeArguments().stream().flatMap(ExtractMethodProvider::typeVariablesIn);
    }

    if (type instanceof final ArrayType array) {
      return typeVariablesIn(array.getComponentType());
    }

    return type instanceof final WildcardType wildcard
        ? Stream.of(wildcard.getExtendsBound(), wildcard.getSuperBound())
            .filter(Objects::nonNull)
            .flatMap(ExtractMethodProvider::typeVariablesIn)
        : Stream.empty();
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
