package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
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

    final var callPath = findEnclosingCall(cursorPath);
    if (callPath == null) {
      return null;
    }

    final var trees = analysis.trees();
    final var fmt = new TypeDisplayFormatter(analysis.types());

    final ExecutableElement resolved;
    final List<ExecutableElement> overloads;
    final String callName;
    final List<? extends Tree> args;

    if (callPath.getLeaf() instanceof final MethodInvocationTree inv) {
      final var sel = trees.getElement(new TreePath(callPath, inv.getMethodSelect()));
      if (!(sel instanceof ExecutableElement exe)) {
        return null;
      }

      resolved = exe;
      if (resolved.getKind() == ElementKind.CONSTRUCTOR) {
        final var owner = enclosingType(resolved);
        if (owner == null) {
          return null;
        }

        callName = owner.getSimpleName().toString();
        overloads = constructorsOf(analysis, owner);
      } else {
        callName = resolved.getSimpleName().toString();
        overloads =
            methodsNamed(
                analysis, (TypeElement) resolved.getEnclosingElement(), resolved.getSimpleName());
      }

      args = inv.getArguments();
    } else {
      final var element = trees.getElement(callPath);
      if (!(element instanceof ExecutableElement exe)) {
        return null;
      }

      resolved = exe;
      final var owner = enclosingType(resolved);
      if (owner == null) {
        return null;
      }

      callName = owner.getSimpleName().toString();
      overloads = constructorsOf(analysis, owner);
      args = ((NewClassTree) callPath.getLeaf()).getArguments();
    }

    if (overloads.isEmpty()) {
      return null;
    }

    final var positions = trees.getSourcePositions();
    final var cu = analysis.tree();
    final int activeParam = activeParamFromArgs(args, positions, cu, cursorOffset);

    final List<SignatureInformation> signatures =
        overloads.stream()
            .map(m -> buildSignature(m, fmt, callName, parser, sourceRoots, javadocLocator, trees))
            .toList();
    final int activeSignature =
        IntStream.range(0, overloads.size())
            .filter(i -> overloads.get(i).equals(resolved))
            .findFirst()
            .orElse(0);

    LOG.fine(
        () ->
            "[signatureHelp] sig=%s param=%d"
                .formatted(signatures.get(activeSignature).getLabel(), activeParam));
    return new SignatureHelp(signatures, activeSignature, activeParam);
  }

  private static TreePath findEnclosingCall(TreePath path) {
    while (path != null) {
      if (path.getLeaf() instanceof MethodInvocationTree
          || path.getLeaf() instanceof NewClassTree) {
        return path;
      }

      path = path.getParentPath();
    }
    return null;
  }

  private static List<ExecutableElement> methodsNamed(
      final AttributedFileAnalysis analysis, final TypeElement owner, final Name name) {
    return analysis.elements().getAllMembers(owner).stream()
        .filter(e -> e.getKind() == ElementKind.METHOD && e.getSimpleName().equals(name))
        .map(e -> (ExecutableElement) e)
        .toList();
  }

  private static List<ExecutableElement> constructorsOf(
      final AttributedFileAnalysis analysis, final TypeElement owner) {
    return analysis.elements().getAllMembers(owner).stream()
        .filter(
            e -> e.getKind() == ElementKind.CONSTRUCTOR && e.getEnclosingElement().equals(owner))
        .map(e -> (ExecutableElement) e)
        .toList();
  }

  private static TypeElement enclosingType(final ExecutableElement element) {
    return element.getEnclosingElement() instanceof TypeElement te ? te : null;
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

    final List<ParameterInformation> paramInfos = new ArrayList<>();
    for (int i = 0; i < params.size(); i++) {
      final int start = label.length();
      label.append(HoverFormatter.formatParam(params.get(i), fmt, sourceNames, i));
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
        .map(HoverFormatter::cleanDoc)
        .filter(doc -> !doc.isBlank())
        .ifPresent(
            doc -> sig.setDocumentation(Either.forRight(new MarkupContent("markdown", doc))));
    return sig;
  }
}
