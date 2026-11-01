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
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.core.CollectionUtil;
import io.github.aglibs.lathe.server.analysis.ExtractionSupport.Site;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.Types;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

// Extract method: the selected expression, or run of statements, becomes a private method placed
// after the member holding it, and the selection a call to it; a second action also replaces its
// copies in the class (statement copies may use other locals). An expression's method returns its
// value. Statements return the one local they assign or declare that is used afterwards
// (`T x = extracted(…);` or `x = extracted(…);`), the method's value when every path returns one
// (`return extracted(…);`), or nothing. RegionFlow decides the parameters and refuses what would
// not keep behaviour.
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
      returnType = returnTypeOf(member, trees);
    } else {
      returnType =
          flow.outputs().stream()
              .findFirst()
              .map(Element::asType)
              .orElse(analysis.types().getNoType(TypeKind.VOID));
    }

    // Every type the new method writes; a void expression is refused below as one that cannot be.
    final List<TypeMirror> thrown = UncaughtExceptions.of(region, analysis);
    final List<TypeMirror> signatureTypes =
        Stream.of(
                expressionForm || returnType.getKind() != TypeKind.VOID
                    ? Stream.of(returnType)
                    : Stream.<TypeMirror>empty(),
                flow.parameters().stream().map(Element::asType),
                flow.declaredHere().stream().map(Element::asType),
                thrown.stream())
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
                    : Stream.of("a type cannot be written"),
                inferredOnlyFromTarget(returnType, flow.parameters())
                    ? Stream.of("a type variable only the return type names")
                    : Stream.<String>empty())
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
            flow.assigned(),
            flow.returnsValue(),
            thrown,
            signatureTypes);
    // A static method cannot use the class's type variables.
    final boolean classTypeVariables =
        typeVariables.stream()
            .anyMatch(variable -> variable.getGenericElement() instanceof TypeElement);
    final var actions = new ArrayList<Either<Command, CodeAction>>();
    final var regionSite = new Site(region, Map.of());
    final boolean regionStatic = isStaticContext(region.get(0), trees);
    if (!(regionStatic && classTypeVariables)) {
      actions.add(
          extraction.action(
              uri,
              "Extract method '%s'".formatted(extraction.name()),
              List.of(regionSite),
              regionStatic));
    }

    final List<Site> copies =
        expressionForm
            ? copies(member.getParentPath(), region.get(0), analysis).stream()
                .map(copy -> new Site(List.of(copy), Map.of()))
                .toList()
            : Stream.concat(
                    Stream.of(regionSite),
                    ExtractionSupport.statementCopies(member.getParentPath(), region, trees)
                        .stream()
                        .filter(copy -> sameFlow(copy, flow, member, analysis)))
                .toList();
    final boolean copiesStatic =
        copies.stream().anyMatch(copy -> isStaticContext(copy.run().get(0), trees));
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

  // The new method, and the edits that call it from sites: the region itself, or its copies.
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
      Set<VariableElement> assigned,
      boolean returnsValue,
      List<TypeMirror> thrown,
      List<TypeMirror> signatureTypes) {

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
          .notNull(assigned, "assigned")
          .notNull(thrown, "thrown")
          .notNull(signatureTypes, "signatureTypes")
          .validate();
      region = List.copyOf(region);
      typeParameters = List.copyOf(typeParameters);
      parameters = List.copyOf(parameters);
      declaredHere = List.copyOf(declaredHere);
      outputs = List.copyOf(outputs);
      assigned = Set.copyOf(assigned);
      thrown = List.copyOf(thrown);
      signatureTypes = List.copyOf(signatureTypes);
    }

    Either<Command, CodeAction> action(
        final String uri, final String title, final List<Site> sites, final boolean isStatic) {
      return ExtractionSupport.action(
          uri, title, ExtractionSupport.METHOD_KIND, edits(sites, isStatic));
    }

    private boolean expressionForm() {
      return region.get(0).getLeaf() instanceof ExpressionTree;
    }

    private List<TextEdit> edits(final List<Site> sites, final boolean isStatic) {
      final CompilationUnitTree cu = analysis.tree();
      final long memberEnd = end(member);
      return Stream.of(
              Stream.of(
                  ExtractionSupport.replaceEdit(cu, memberEnd, memberEnd, methodText(isStatic))),
              sites.stream()
                  .map(
                      site ->
                          ExtractionSupport.replaceEdit(
                              cu,
                              start(site.run().get(0)),
                              end(CollectionUtil.last(site.run())),
                              call(site))),
              CodeActionSupport.importEditsFor(analysis, signatureTypes.stream()).stream())
          .flatMap(edits -> edits)
          .toList();
    }

    // The call at a site, passing the site's own locals.
    private String call(final Site site) {
      return "%s%s(%s)%s"
          .formatted(
              returnsValue
                  ? "return "
                  : outputs.stream()
                      .map(output -> receiving(site, site.local(output)))
                      .findFirst()
                      .orElse(""),
              name,
              parameters.stream()
                  .map(parameter -> site.local(parameter).getSimpleName().toString())
                  .collect(Collectors.joining(", ")),
              expressionForm() ? "" : ";");
    }

    // Where the call's value lands: the declaration the site made, keeping its `final`, or an
    // earlier local.
    private String receiving(final Site site, final VariableElement output) {
      return site.run().stream()
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
                          typeText(output.asType()),
                          output.getSimpleName()))
          .orElse("%s = ".formatted(output.getSimpleName()));
    }

    private String methodText(final boolean isStatic) {
      final String indent = CodeActionSupport.lineIndent(source, (int) start(member));
      final String step =
          CodeActionSupport.indentStep(
              indent, CodeActionSupport.lineIndent(source, (int) start(member.getParentPath())));
      // A parameter the region assigns cannot be final, whatever the file's style.
      final boolean finalStyle = finalParameters();
      final String parameterList =
          parameters.stream()
              .map(
                  parameter ->
                      "%s%s %s"
                          .formatted(
                              finalStyle && !assigned.contains(parameter) ? "final " : "",
                              typeText(parameter.asType()),
                              parameter.getSimpleName()))
              .collect(Collectors.joining(", "));
      final String throwsClause =
          thrown.isEmpty()
              ? ""
              : " throws " + thrown.stream().map(this::typeText).collect(Collectors.joining(", "));
      final String text = sourceOf(region.get(0), CollectionUtil.last(region));
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
                                          typeText(local.asType()),
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
      return "\n\n%s%sprivate %s%s%s %s(%s)%s {\n%s\n%s}"
          .formatted(
              indent,
              suppressWarnings(indent),
              isStatic ? "static " : "",
              typeParameters.isEmpty()
                  ? ""
                  : "<%s> "
                      .formatted(
                          typeParameters.stream()
                              .map(parameter -> new TreePath(member, parameter))
                              .map(path -> sourceOf(path, path))
                              .collect(Collectors.joining(", "))),
              typeText(returnType),
              name,
              parameterList,
              throwsClause,
              body,
              indent);
    }

    // The warnings the enclosing member suppresses stay suppressed in the code it gives away: a
    // deprecated call moved out would otherwise warn, and fail a -Werror build.
    private String suppressWarnings(final String indent) {
      final ModifiersTree modifiers;
      if (member.getLeaf() instanceof final MethodTree method) {
        modifiers = method.getModifiers();
      } else if (member.getLeaf() instanceof final VariableTree field) {
        modifiers = field.getModifiers();
      } else {
        return "";
      }

      final TreePath modifiersPath = new TreePath(member, modifiers);
      return modifiers.getAnnotations().stream()
          .map(annotation -> new TreePath(modifiersPath, annotation))
          .filter(this::isSuppressWarnings)
          .map(path -> "%s\n%s".formatted(sourceOf(path, path), indent))
          .collect(Collectors.joining());
    }

    private boolean isSuppressWarnings(final TreePath annotation) {
      return analysis
                  .trees()
                  .getElement(
                      new TreePath(
                          annotation, ((AnnotationTree) annotation.getLeaf()).getAnnotationType()))
              instanceof final TypeElement type
          && type.getQualifiedName().contentEquals(SuppressWarnings.class.getName());
    }

    // The file's style: parameters are final when every parameter of the enclosing method is.
    private boolean finalParameters() {
      return member.getLeaf() instanceof final MethodTree method
          && !method.getParameters().isEmpty()
          && method.getParameters().stream()
              .allMatch(parameter -> parameter.getModifiers().getFlags().contains(Modifier.FINAL));
    }

    private String typeText(final TypeMirror type) {
      return CodeActionSupport.sourceTypeFormatter(analysis).format(type);
    }

    private String sourceOf(final TreePath first, final TreePath last) {
      return source.substring((int) start(first), (int) end(last));
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

  // Whether a statement copy computes what the region does, so one method serves both: its locals
  // of the same types, the same parameters, value and return.
  private static boolean sameFlow(
      final Site copy,
      final RegionFlow flow,
      final TreePath member,
      final AttributedFileAnalysis analysis) {
    final Types types = analysis.types();
    final TreePath copyMember = enclosingMember(copy.run().get(0));
    if (copyMember == null
        || !copy.locals().entrySet().stream()
            .allMatch(pair -> types.isSameType(pair.getKey().asType(), pair.getValue().asType()))) {
      return false;
    }

    final RegionFlow copyFlow = RegionFlow.of(copyMember, copy.run(), analysis.trees());
    return copyFlow.refusals().isEmpty()
        && copyFlow.parameters().equals(localsAt(copy, flow.parameters()))
        && copyFlow.declaredHere().equals(localsAt(copy, flow.declaredHere()))
        && copyFlow.outputs().equals(localsAt(copy, flow.outputs()))
        && copyFlow.returnsValue() == flow.returnsValue()
        && (!flow.returnsValue()
            || types.isSameType(
                returnTypeOf(member, analysis.trees()),
                returnTypeOf(copyMember, analysis.trees())));
  }

  private static List<VariableElement> localsAt(
      final Site site, final List<VariableElement> regionLocals) {
    return regionLocals.stream().map(site::local).toList();
  }

  private static TypeMirror returnTypeOf(final TreePath method, final Trees trees) {
    return ((ExecutableElement) trees.getElement(method)).getReturnType();
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
  // annotation, not an assignment's target, not an enum constant's own creation (its source is only
  // the argument list).
  private static boolean replaceableByCall(final TreePath exprPath, final Trees trees) {
    return !ExtractionSupport.isCompileTimeConstant(exprPath, trees)
        && !ExtractionSupport.isAssignmentTarget(exprPath)
        && !isEnumConstantCreation(exprPath, trees)
        && Stream.iterate(exprPath, Objects::nonNull, TreePath::getParentPath)
            .noneMatch(path -> path.getLeaf() instanceof AnnotationTree || isCaseLabel(path));
  }

  private static boolean isEnumConstantCreation(final TreePath path, final Trees trees) {
    if (!(path.getParentPath().getLeaf() instanceof VariableTree)) {
      return false;
    }

    final Element declared = trees.getElement(path.getParentPath());
    return declared != null && declared.getKind() == ElementKind.ENUM_CONSTANT;
  }

  private static boolean isCaseLabel(final TreePath path) {
    return path.getParentPath() != null
        && path.getParentPath().getLeaf() instanceof final CaseTree caseTree
        && caseTree.getExpressions().contains(path.getLeaf());
  }

  // The selection and its exact copies among the class's own members: the same shape reading the
  // same variables, so each copy's inputs are in scope there. The same type too: a poly expression
  // (`List.of()`) takes its type from where it stands, and one method returns one type.
  private static List<TreePath> copies(
      final TreePath classPath, final TreePath exprPath, final AttributedFileAnalysis analysis) {
    final Trees trees = analysis.trees();
    final TypeMirror type = trees.getTypeMirror(exprPath);
    return ExtractionSupport.occurrences(classPath, exprPath, trees).stream()
        .filter(copy -> CodeActionSupport.enclosingClass(copy).getLeaf() == classPath.getLeaf())
        .filter(copy -> analysis.types().isSameType(trees.getTypeMirror(copy), type))
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

  // A method type variable the return type names but no parameter does is inferred from where the
  // call stands, and a call in a `var` declaration or an argument gives it nothing to go by.
  private static boolean inferredOnlyFromTarget(
      final TypeMirror returnType, final List<VariableElement> parameters) {
    final Set<TypeParameterElement> fromArguments =
        parameters.stream()
            .map(Element::asType)
            .flatMap(ExtractMethodProvider::typeVariablesIn)
            .collect(Collectors.toUnmodifiableSet());
    return typeVariablesIn(returnType)
        .anyMatch(
            variable ->
                variable.getGenericElement() instanceof ExecutableElement
                    && !fromArguments.contains(variable));
  }

  private static Stream<TypeParameterElement> typeVariablesIn(final TypeMirror type) {
    return CodeActionSupport.typesIn(type)
        .filter(TypeVariable.class::isInstance)
        .map(variable -> (TypeParameterElement) ((TypeVariable) variable).asElement());
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
