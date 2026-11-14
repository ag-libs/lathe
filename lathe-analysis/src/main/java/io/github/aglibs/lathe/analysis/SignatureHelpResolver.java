package io.github.aglibs.lathe.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.core.CollectionUtil;
import io.github.aglibs.validcheck.ValidCheck;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.util.ElementFilter;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Tuple;

final class SignatureHelpResolver {

  private static final Logger LOG = Logger.getLogger(SignatureHelpResolver.class.getName());

  private SignatureHelpResolver() {}

  static SignatureHelp resolve(
      final AttributedFileAnalysis analysis,
      final TreePath cursorPath,
      final long cursorOffset,
      final SourceParser parser,
      final List<Path> sourceRoots,
      final JavadocLocator javadocLocator) {
    if (cursorPath == null) {
      return null;
    }

    final var positions = analysis.trees().getSourcePositions();
    final var cu = analysis.tree();
    final var callPath = findEnclosingCall(cursorPath, positions, cu, cursorOffset);
    if (callPath == null) {
      return null;
    }

    final var target = callTarget(analysis, callPath);
    if (target == null || target.overloads().isEmpty()) {
      return null;
    }

    final var fmt = new TypeDisplayFormatter(analysis.types());
    final int activeParam = activeParamFromArgs(target.args(), positions, cu, cursorOffset);
    // One reader for every overload: overloads share ancestors, each parsed once.
    final DocReader reader = javadocLocator.reader(analysis, sourceRoots);
    final List<SignatureInformation> signatures =
        target.overloads().stream()
            .map(
                m ->
                    buildSignature(
                        m, fmt, target.name(), parser, sourceRoots, javadocLocator, reader))
            .toList();
    final int activeSignature = activeSignature(target);

    LOG.fine(
        () ->
            "[signatureHelp] sig=%s param=%d"
                .formatted(signatures.get(activeSignature).getLabel(), activeParam));
    return new SignatureHelp(signatures, activeSignature, activeParam);
  }

  // The overloads a method call or `new` expression can name when javac has not settled which:
  // it resolves none of them, or the call is being typed (`beingTyped`) and its binding a guess.
  // Empty for a call javac settled.
  static List<ExecutableElement> unresolvedOverloads(
      final AttributedFileAnalysis analysis, final TreePath callPath, final boolean beingTyped) {
    final var target = callTarget(analysis, callPath);
    if (target == null) {
      return List.of();
    }

    return target.resolved() == null || beingTyped ? target.overloads() : List.of();
  }

  // Whether a call is still being typed, so javac's binding of it is a guess: an argument has an
  // error type (javac's placeholder for a missing argument, a half-typed `name.` or `trim(`, an
  // unknown name), or the call has no closing `)` and javac took the next statement for its last
  // argument, so the call ends where that argument does. A closed call always ends after its
  // last argument. False for anything but a method call or `new`.
  static boolean beingTyped(final AttributedFileAnalysis analysis, final TreePath callPath) {
    final List<? extends ExpressionTree> args = arguments(callPath.getLeaf());
    if (args.isEmpty()) {
      return false;
    }

    final var trees = analysis.trees();
    final boolean errorTyped =
        args.stream()
            .map(arg -> trees.getTypeMirror(new TreePath(callPath, arg)))
            .anyMatch(type -> type != null && type.getKind() == TypeKind.ERROR);
    final var positions = trees.getSourcePositions();
    final CompilationUnitTree cu = analysis.tree();
    final boolean unclosed =
        positions.getEndPosition(cu, callPath.getLeaf())
            == positions.getEndPosition(cu, CollectionUtil.last(args));
    return errorTyped || unclosed;
  }

  private static List<? extends ExpressionTree> arguments(final Tree call) {
    if (call instanceof final MethodInvocationTree invocation) {
      return invocation.getArguments();
    }

    return call instanceof final NewClassTree creation ? creation.getArguments() : List.of();
  }

  private static CallTarget callTarget(
      final AttributedFileAnalysis analysis, final TreePath callPath) {
    return callPath.getLeaf() instanceof final MethodInvocationTree inv
        ? invocationTarget(analysis, callPath, inv)
        : constructionTarget(analysis, callPath, (NewClassTree) callPath.getLeaf());
  }

  // Every accessible overload a call can name, whether or not it attributes yet: javac resolves
  // nothing for `new P()` or `super()` when no constructor takes zero arguments. Access is checked
  // through the type the call reaches its overloads from, as javac does, not the declaring class.
  private static CallTarget invocationTarget(
      final AttributedFileAnalysis analysis,
      final TreePath callPath,
      final MethodInvocationTree inv) {
    final var trees = analysis.trees();
    final var scope = trees.getScope(callPath);
    final var select = inv.getMethodSelect();
    final var args = inv.getArguments();
    final var resolved =
        trees.getElement(new TreePath(callPath, select)) instanceof final ExecutableElement exe
            ? exe
            : null;
    final var self = enclosingClass(callPath, trees);
    if (select instanceof final MemberSelectTree member) {
      if (!(trees.getTypeMirror(
                  new TreePath(new TreePath(callPath, member), member.getExpression()))
              instanceof final DeclaredType receiver)
          || !(receiver.asElement() instanceof final TypeElement owner)) {
        return null;
      }

      // `super.m()` reaches m through the calling class, which protected access is checked from.
      final var site = isNamed(member.getExpression(), "super") && self != null ? self : owner;
      return methodTarget(analysis, scope, owner, site, member.getIdentifier(), resolved, args);
    }

    if (!(select instanceof final IdentifierTree id) || self == null) {
      return null;
    }

    if (isNamed(id, "this")) {
      return constructorTarget(self, resolved, args, constructor -> true);
    }

    if (isNamed(id, "super")) {
      return analysis.types().asElement(self.getSuperclass()) instanceof final TypeElement parent
          ? constructorTarget(
              parent,
              resolved,
              args,
              constructor -> reachableFromSubclass(analysis, constructor, self))
          : null;
    }

    final var owner = innermostDeclaring(analysis, callPath, id.getName()).orElse(self);
    return methodTarget(analysis, scope, owner, owner, id.getName(), resolved, args);
  }

  private static CallTarget constructionTarget(
      final AttributedFileAnalysis analysis, final TreePath callPath, final NewClassTree call) {
    final var trees = analysis.trees();
    if (!(trees.getElement(new TreePath(callPath, call.getIdentifier()))
        instanceof final TypeElement owner)) {
      return null;
    }

    final var resolved =
        trees.getElement(callPath) instanceof final ExecutableElement exe ? exe : null;
    if (call.getClassBody() == null) {
      final var scope = trees.getScope(callPath);
      final var site = (DeclaredType) owner.asType();
      return constructorTarget(
          owner,
          resolved,
          call.getArguments(),
          constructor -> trees.isAccessible(scope, constructor, site));
    }

    // javac resolves the anonymous class's own constructor, which mirrors the one it calls.
    final var self = enclosingClass(callPath, trees);
    final var called =
        ElementFilter.constructorsIn(owner.getEnclosedElements()).stream()
            .filter(constructor -> sameParameters(analysis, constructor, resolved))
            .findFirst()
            .orElse(null);
    return constructorTarget(
        owner,
        called,
        call.getArguments(),
        constructor -> self != null && reachableFromSubclass(analysis, constructor, self));
  }

  // A subclass reaches its superclass's constructors through `super(…)` or an anonymous body, which
  // javac's public access check cannot express (it needs the `super` call's own context), so the
  // subclass rule is applied directly.
  private static boolean reachableFromSubclass(
      final AttributedFileAnalysis analysis,
      final ExecutableElement constructor,
      final TypeElement subclass) {
    final Set<Modifier> modifiers = constructor.getModifiers();
    if (modifiers.contains(Modifier.PUBLIC) || modifiers.contains(Modifier.PROTECTED)) {
      return true;
    }

    if (modifiers.contains(Modifier.PRIVATE)) {
      return outermost(constructor).equals(outermost(subclass));
    }

    final var elements = analysis.elements();
    return elements.getPackageOf(constructor).equals(elements.getPackageOf(subclass));
  }

  private static Element outermost(final Element element) {
    return Stream.iterate(
            element, e -> !(e instanceof PackageElement), Element::getEnclosingElement)
        .reduce((inner, outer) -> outer)
        .orElse(element);
  }

  // The innermost enclosing class with a method of that name: the class an unqualified call
  // reaches its overloads from.
  private static Optional<TypeElement> innermostDeclaring(
      final AttributedFileAnalysis analysis, final TreePath callPath, final Name name) {
    return Stream.iterate(callPath, Objects::nonNull, TreePath::getParentPath)
        .filter(path -> path.getLeaf() instanceof ClassTree)
        .map(path -> analysis.trees().getElement(path))
        .filter(TypeElement.class::isInstance)
        .map(TypeElement.class::cast)
        .filter(type -> !methodsNamed(analysis, type, name).isEmpty())
        .findFirst();
  }

  private static boolean isNamed(final Tree tree, final String name) {
    return tree instanceof final IdentifierTree id && id.getName().contentEquals(name);
  }

  private static boolean sameParameters(
      final AttributedFileAnalysis analysis,
      final ExecutableElement constructor,
      final ExecutableElement other) {
    final var types = analysis.types();
    return other != null
        && constructor.getParameters().size() == other.getParameters().size()
        && IntStream.range(0, constructor.getParameters().size())
            .allMatch(
                i ->
                    types.isSameType(
                        types.erasure(constructor.getParameters().get(i).asType()),
                        types.erasure(other.getParameters().get(i).asType())));
  }

  private static List<ExecutableElement> methodsNamed(
      final AttributedFileAnalysis analysis, final TypeElement owner, final Name name) {
    return ElementFilter.methodsIn(analysis.elements().getAllMembers(owner)).stream()
        .filter(m -> m.getSimpleName().equals(name))
        .toList();
  }

  private static CallTarget methodTarget(
      final AttributedFileAnalysis analysis,
      final Scope scope,
      final TypeElement owner,
      final TypeElement site,
      final Name name,
      final ExecutableElement resolved,
      final List<? extends Tree> args) {
    final var siteType = (DeclaredType) site.asType();
    return new CallTarget(
        name.toString(),
        methodsNamed(analysis, owner, name).stream()
            .filter(method -> analysis.trees().isAccessible(scope, method, siteType))
            .toList(),
        resolved,
        args);
  }

  private static CallTarget constructorTarget(
      final TypeElement owner,
      final ExecutableElement resolved,
      final List<? extends Tree> args,
      final Predicate<ExecutableElement> reachable) {
    return new CallTarget(
        owner.getSimpleName().toString(),
        ElementFilter.constructorsIn(owner.getEnclosedElements()).stream()
            .filter(reachable)
            .toList(),
        resolved,
        args);
  }

  // The resolved overload; for a call that does not attribute yet, the first whose arity fits the
  // arguments typed so far.
  private static int activeSignature(final CallTarget target) {
    final List<ExecutableElement> overloads = target.overloads();
    final int argCount = target.args().size();
    return IntStream.range(0, overloads.size())
        .filter(i -> overloads.get(i).equals(target.resolved()))
        .findFirst()
        .orElseGet(
            () ->
                IntStream.range(0, overloads.size())
                    .filter(i -> fitsArity(overloads.get(i), argCount))
                    .findFirst()
                    .orElse(0));
  }

  private static boolean fitsArity(final ExecutableElement method, final int argCount) {
    final int params = method.getParameters().size();
    return method.isVarArgs() ? argCount >= params - 1 : argCount <= params;
  }

  private static TreePath findEnclosingCall(
      TreePath path,
      final SourcePositions positions,
      final CompilationUnitTree cu,
      final long cursorOffset) {
    while (path != null) {
      if (path.getLeaf() instanceof final MethodInvocationTree inv) {
        final long selectEnd = positions.getEndPosition(cu, inv.getMethodSelect());
        if (cursorOffset > selectEnd) {
          return path;
        }
      } else if (path.getLeaf() instanceof final NewClassTree newClass) {
        final long identEnd = positions.getEndPosition(cu, newClass.getIdentifier());
        if (cursorOffset > identEnd) {
          return path;
        }
      }

      path = path.getParentPath();
    }
    return null;
  }

  private static TypeElement enclosingClass(TreePath path, final Trees trees) {
    path = path.getParentPath();
    while (path != null) {
      if (path.getLeaf() instanceof ClassTree && trees.getElement(path) instanceof TypeElement te) {
        return te;
      }

      path = path.getParentPath();
    }
    return null;
  }

  private static int activeParamFromArgs(
      final List<? extends Tree> args,
      final SourcePositions positions,
      final CompilationUnitTree cu,
      final long cursorOffset) {
    for (int i = 0; i < args.size(); i++) {
      if (cursorOffset < positions.getStartPosition(cu, args.get(i))) {
        return i;
      }

      if (cursorOffset < positions.getEndPosition(cu, args.get(i))) {
        return i;
      }
    }
    return Math.max(0, args.size() - 1);
  }

  private static SignatureInformation buildSignature(
      final ExecutableElement method,
      final TypeDisplayFormatter fmt,
      final String callName,
      final SourceParser parser,
      final List<Path> sourceRoots,
      final JavadocLocator javadocLocator,
      final DocReader reader) {
    final var params = method.getParameters();
    final var label = new StringBuilder();

    final List<String> sourceNames = parser.resolveParamNames(method, sourceRoots);

    if (method.getKind() != ElementKind.CONSTRUCTOR) {
      label.append(fmt.format(method.getReturnType())).append(' ');
    }

    label.append(callName).append('(');

    final var paramInfos = new ArrayList<ParameterInformation>();
    for (int i = 0; i < params.size(); i++) {
      final int start = label.length();
      label.append(
          HoverFormatter.formatParam(
              params.get(i), fmt, sourceNames, i, method.isVarArgs() && i == params.size() - 1));
      final int end = label.length();

      if (i < params.size() - 1) {
        label.append(", ");
      }

      final var paramInfo = new ParameterInformation();
      paramInfo.setLabel(Tuple.two(start, end));
      paramInfos.add(paramInfo);
    }
    label.append(')');

    final var sig = new SignatureInformation(label.toString());
    sig.setParameters(paramInfos);
    javadocLocator
        .signatureDocs(method, reader)
        .ifPresent(
            docs -> {
              if (!docs.description().isBlank()) {
                sig.setDocumentation(
                    Either.forRight(new MarkupContent("markdown", docs.description())));
              }

              docs.paramDocs()
                  .forEach(
                      (index, doc) ->
                          paramInfos
                              .get(index)
                              .setDocumentation(new MarkupContent("markdown", doc)));
            });
    return sig;
  }

  // The overloads a call can name, and the one javac resolved it to (null until the call
  // attributes).
  private record CallTarget(
      String name,
      List<ExecutableElement> overloads,
      ExecutableElement resolved,
      List<? extends Tree> args) {

    CallTarget {
      ValidCheck.check().notBlank(name, "name").notNull(args, "args").validate();
      overloads = List.copyOf(overloads);
      args = List.copyOf(args);
    }
  }
}
