package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import io.github.aglibs.validcheck.ValidCheck;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
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

    final var target =
        callPath.getLeaf() instanceof final MethodInvocationTree inv
            ? invocationTarget(analysis, callPath, inv)
            : constructionTarget(analysis, callPath, (NewClassTree) callPath.getLeaf());
    if (target == null || target.overloads().isEmpty()) {
      return null;
    }

    final var trees = analysis.trees();
    final var fmt = new TypeDisplayFormatter(analysis.types());
    final int activeParam = activeParamFromArgs(target.args(), positions, cu, cursorOffset);
    final List<SignatureInformation> signatures =
        target.overloads().stream()
            .map(
                m ->
                    buildSignature(
                        m, fmt, target.name(), parser, sourceRoots, javadocLocator, trees))
            .toList();
    final int activeSignature = activeSignature(target);

    LOG.fine(
        () ->
            "[signatureHelp] sig=%s param=%d"
                .formatted(signatures.get(activeSignature).getLabel(), activeParam));
    return new SignatureHelp(signatures, activeSignature, activeParam);
  }

  // Every accessible overload a call can name, whether or not it attributes yet: javac resolves
  // nothing for `new P()` or `super()` when no constructor takes zero arguments.
  private static CallTarget invocationTarget(
      final AttributedFileAnalysis analysis,
      final TreePath callPath,
      final MethodInvocationTree inv) {
    final var trees = analysis.trees();
    final var scope = trees.getScope(callPath);
    final var select = inv.getMethodSelect();
    final var args = inv.getArguments();
    if (trees.getElement(new TreePath(callPath, select)) instanceof final ExecutableElement exe
        && exe.getEnclosingElement() instanceof final TypeElement owner) {
      return exe.getKind() == ElementKind.CONSTRUCTOR
          ? constructorTarget(analysis, scope, owner, exe, args)
          : methodTarget(analysis, scope, owner, exe.getSimpleName(), exe, args);
    }

    if (select instanceof final IdentifierTree id) {
      final var self = enclosingClass(callPath, trees);
      if (self == null) {
        return null;
      }

      if (id.getName().contentEquals("this")) {
        return constructorTarget(analysis, scope, self, null, args);
      }

      if (id.getName().contentEquals("super")) {
        return analysis.types().asElement(self.getSuperclass()) instanceof final TypeElement parent
            ? constructorTarget(analysis, scope, parent, null, args)
            : null;
      }

      return methodTarget(analysis, scope, self, id.getName(), null, args);
    }

    if (select instanceof final MemberSelectTree member
        && trees.getTypeMirror(new TreePath(new TreePath(callPath, member), member.getExpression()))
            instanceof final DeclaredType receiver
        && receiver.asElement() instanceof final TypeElement owner) {
      return methodTarget(analysis, scope, owner, member.getIdentifier(), null, args);
    }

    return null;
  }

  private static CallTarget constructionTarget(
      final AttributedFileAnalysis analysis, final TreePath callPath, final NewClassTree call) {
    final var trees = analysis.trees();
    final var resolved =
        trees.getElement(callPath) instanceof final ExecutableElement exe ? exe : null;
    return trees.getElement(new TreePath(callPath, call.getIdentifier()))
            instanceof final TypeElement owner
        ? constructorTarget(
            analysis, trees.getScope(callPath), owner, resolved, call.getArguments())
        : null;
  }

  private static CallTarget methodTarget(
      final AttributedFileAnalysis analysis,
      final Scope scope,
      final TypeElement owner,
      final Name name,
      final ExecutableElement resolved,
      final List<? extends Tree> args) {
    final Stream<ExecutableElement> methods =
        ElementFilter.methodsIn(analysis.elements().getAllMembers(owner)).stream()
            .filter(m -> m.getSimpleName().equals(name));
    return new CallTarget(
        name.toString(), accessible(analysis, scope, owner, methods), resolved, args);
  }

  private static CallTarget constructorTarget(
      final AttributedFileAnalysis analysis,
      final Scope scope,
      final TypeElement owner,
      final ExecutableElement resolved,
      final List<? extends Tree> args) {
    final Stream<ExecutableElement> constructors =
        ElementFilter.constructorsIn(owner.getEnclosedElements()).stream();
    return new CallTarget(
        owner.getSimpleName().toString(),
        accessible(analysis, scope, owner, constructors),
        resolved,
        args);
  }

  private static List<ExecutableElement> accessible(
      final AttributedFileAnalysis analysis,
      final Scope scope,
      final TypeElement owner,
      final Stream<ExecutableElement> members) {
    final var site = (DeclaredType) owner.asType();
    return members.filter(m -> analysis.trees().isAccessible(scope, m, site)).toList();
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
      final Trees trees) {
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
        .locate(method, trees, sourceRoots)
        .ifPresent(
            tree -> {
              final String mainDesc = JavadocMarkdownPrinter.mainDescription(tree);
              if (!mainDesc.isBlank()) {
                sig.setDocumentation(Either.forRight(new MarkupContent("markdown", mainDesc)));
              }

              final Map<String, String> pdocs = JavadocMarkdownPrinter.paramDocs(tree);
              if (!pdocs.isEmpty()) {
                for (int i = 0; i < params.size(); i++) {
                  final String name =
                      sourceNames != null && i < sourceNames.size()
                          ? sourceNames.get(i)
                          : params.get(i).getSimpleName().toString();
                  final String doc = pdocs.get(name);
                  if (doc != null) {
                    paramInfos.get(i).setDocumentation(new MarkupContent("markdown", doc));
                  }
                }
              }
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
