package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Types;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

final class MissingMethodImplProvider implements CodeActionProvider {

  private static final Logger LOG = Logger.getLogger(MissingMethodImplProvider.class.getName());

  @Override
  public List<Either<Command, CodeAction>> provide(
      final CodeActionRequest request,
      final AttributedFileAnalysis analysis,
      final WorkspaceTypeIndex typeIndex) {
    if (analysis.tree() == null) {
      return List.of();
    }

    final CompilationUnitTree cu = analysis.tree();
    final String className = request.payload().name();
    final TreePath classPath = findClassPath(cu, className);
    if (classPath == null) {
      return List.of();
    }

    final TypeElement classElement = (TypeElement) analysis.trees().getElement(classPath);
    if (classElement == null) {
      return List.of();
    }

    final List<ExecutableElement> unimplemented = collectUnimplemented(classElement, analysis);
    if (unimplemented.isEmpty()) {
      return List.of();
    }

    final long endOffset =
        analysis.trees().getSourcePositions().getEndPosition(cu, classPath.getLeaf());
    if (endOffset < 0) {
      return List.of();
    }

    final var formatter = new TypeDisplayFormatter(analysis.types());
    final var importAnalyzer = new ImportAnalyzer(analysis);
    final Types types = analysis.types();
    final DeclaredType classType = (DeclaredType) classElement.asType();

    final var insertPos = SourceLocator.offsetToPosition(cu, endOffset - 1);
    final TextEdit stubEdit =
        new TextEdit(
            new Range(insertPos, insertPos),
            buildStubs(unimplemented, formatter, types, classType));

    final List<TextEdit> importEdits =
        unimplemented.stream()
            .flatMap(m -> methodFqns(m, types, classType).stream())
            .distinct()
            .map(importAnalyzer::importEdit)
            .filter(Objects::nonNull)
            .toList();

    final List<TextEdit> allEdits =
        Stream.concat(Stream.of(stubEdit), importEdits.stream()).toList();

    final var action = new CodeAction();
    action.setTitle("Implement abstract methods");
    action.setKind(CodeActionKind.QuickFix);
    action.setDiagnostics(List.of(request.diag()));

    final var workspaceEdit = new WorkspaceEdit();
    workspaceEdit.setChanges(Map.of(request.uri(), allEdits));
    action.setEdit(workspaceEdit);

    LOG.fine(
        () -> "[codeAction:missingImpl] %s methods=%d".formatted(className, unimplemented.size()));
    return List.of(Either.forRight(action));
  }

  private static TreePath findClassPath(final CompilationUnitTree cu, final String simpleName) {
    final var scanner =
        new TreePathScanner<TreePath, Void>() {
          @Override
          public TreePath visitClass(final ClassTree node, final Void v) {
            if (node.getSimpleName().contentEquals(simpleName)) {
              return getCurrentPath();
            }
            return super.visitClass(node, v);
          }

          @Override
          public TreePath reduce(final TreePath r1, final TreePath r2) {
            return r1 != null ? r1 : r2;
          }
        };
    return scanner.scan(cu, null);
  }

  private static List<ExecutableElement> collectUnimplemented(
      final TypeElement classElement, final AttributedFileAnalysis analysis) {
    final Set<String> ownSignatures =
        classElement.getEnclosedElements().stream()
            .filter(e -> e instanceof ExecutableElement && e.getKind() == ElementKind.METHOD)
            .map(e -> signatureKey((ExecutableElement) e))
            .collect(Collectors.toUnmodifiableSet());

    return analysis.elements().getAllMembers(classElement).stream()
        .filter(e -> e instanceof ExecutableElement exe && exe.getKind() == ElementKind.METHOD)
        .map(ExecutableElement.class::cast)
        .filter(e -> e.getModifiers().contains(Modifier.ABSTRACT))
        .filter(e -> !e.getEnclosingElement().equals(classElement))
        .filter(e -> !ownSignatures.contains(signatureKey(e)))
        .toList();
  }

  private static String signatureKey(final ExecutableElement exe) {
    final String params =
        exe.getParameters().stream()
            .map(p -> p.asType().toString())
            .collect(Collectors.joining(","));
    return "%s(%s)".formatted(exe.getSimpleName(), params);
  }

  private static String buildStubs(
      final List<ExecutableElement> methods,
      final TypeDisplayFormatter formatter,
      final Types types,
      final DeclaredType classType) {
    return methods.stream()
            .map(m -> buildStub(m, formatter, types, classType))
            .collect(Collectors.joining())
        + "\n";
  }

  private static String buildStub(
      final ExecutableElement method,
      final TypeDisplayFormatter formatter,
      final Types types,
      final DeclaredType classType) {
    return """


            @Override
            public %s {
                throw new UnsupportedOperationException();
            }"""
        .formatted(MethodStubRenderer.signature(method, classType, types, formatter));
  }

  private static Set<String> methodFqns(
      final ExecutableElement method, final Types types, final DeclaredType classType) {
    final ExecutableType exeType = (ExecutableType) types.asMemberOf(classType, method);
    return Stream.concat(
            Stream.concat(Stream.of(exeType.getReturnType()), exeType.getParameterTypes().stream()),
            exeType.getThrownTypes().stream())
        .flatMap(t -> collectFqns(t).stream())
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Set<String> collectFqns(final TypeMirror type) {
    return switch (type) {
      case DeclaredType dt -> {
        final Stream<String> self =
            dt.asElement() instanceof TypeElement te
                ? Stream.of(te.getQualifiedName().toString())
                : Stream.of();
        final Stream<String> args =
            dt.getTypeArguments().stream().flatMap(arg -> collectFqns(arg).stream());
        yield Stream.concat(self, args).collect(Collectors.toUnmodifiableSet());
      }
      case ArrayType at -> collectFqns(at.getComponentType());
      case WildcardType wt -> {
        final Stream<String> ext =
            wt.getExtendsBound() != null ? collectFqns(wt.getExtendsBound()).stream() : Stream.of();
        final Stream<String> sup =
            wt.getSuperBound() != null ? collectFqns(wt.getSuperBound()).stream() : Stream.of();
        yield Stream.concat(ext, sup).collect(Collectors.toUnmodifiableSet());
      }
      default -> Set.of();
    };
  }
}
