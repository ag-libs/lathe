package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.tools.JavaFileObject;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

final class JavacDiagnosticMapper {

  private JavacDiagnosticMapper() {}

  public static List<Diagnostic> filterAndMap(
      final List<? extends javax.tools.Diagnostic<? extends JavaFileObject>> raw,
      final String content) {
    final var seen = new HashSet<String>();
    return raw.stream()
        .filter(
            d ->
                d.getKind() != javax.tools.Diagnostic.Kind.NOTE
                    || d.getPosition() != javax.tools.Diagnostic.NOPOS)
        .map(d -> toLsp(d, content))
        .filter(d -> notDuplicate(d, seen))
        .toList();
  }

  public static Diagnostic toLsp(
      final javax.tools.Diagnostic<? extends JavaFileObject> d, final String content) {
    final var severity =
        switch (d.getKind()) {
          case ERROR -> DiagnosticSeverity.Error;
          case WARNING, MANDATORY_WARNING -> DiagnosticSeverity.Warning;
          case NOTE -> DiagnosticSeverity.Hint;
          default -> DiagnosticSeverity.Information;
        };

    final long startOffset = d.getStartPosition();
    final long endOffset = d.getEndPosition();

    final Position start;
    final Position end;
    if (startOffset != javax.tools.Diagnostic.NOPOS) {
      start = SourceLocator.offsetToPosition(content, startOffset);
      end =
          (endOffset != javax.tools.Diagnostic.NOPOS && endOffset > startOffset)
              ? SourceLocator.offsetToPosition(content, endOffset)
              : new Position(start.getLine(), start.getCharacter() + 1);
    } else {
      final long rawLine = d.getLineNumber();
      final long rawCol = d.getColumnNumber();
      final int line = rawLine == javax.tools.Diagnostic.NOPOS ? 0 : (int) rawLine - 1;
      final int col = rawCol == javax.tools.Diagnostic.NOPOS ? 0 : (int) rawCol - 1;
      start = new Position(line, col);
      end = new Position(line, col + 1);
    }

    final var diagnostic =
        new Diagnostic(new Range(start, end), d.getMessage(Locale.ENGLISH), severity, "lathe");
    if (d.getCode() != null) {
      diagnostic.setCode(d.getCode());
      if (d.getCode().startsWith("compiler.err.cant.resolve")
          && startOffset != javax.tools.Diagnostic.NOPOS
          && endOffset > startOffset
          && endOffset <= content.length()) {
        diagnostic.setData(content.substring((int) startOffset, (int) endOffset));
      }
    }

    return diagnostic;
  }

  static void enrich(final List<Diagnostic> diags, final AttributedFileAnalysis analysis) {
    for (final var diag : diags) {
      final Either<String, Integer> codeEither = diag.getCode();
      if (codeEither == null || !codeEither.isLeft()) {
        continue;
      }

      final String code = codeEither.getLeft();
      if (code.startsWith("compiler.err.cant.resolve") && diag.getData() instanceof String name) {
        diag.setData(new DiagnosticPayload(resolveKind(diag, analysis), name));
      } else if (code.startsWith("compiler.err.unreported.exception")) {
        final Either<String, ?> msgEither = diag.getMessage();
        final String name =
            extractExceptionName(
                msgEither != null && msgEither.isLeft() ? msgEither.getLeft() : null);
        if (name != null) {
          diag.setData(new DiagnosticPayload(DiagnosticPayload.Kind.UNREPORTED_EXCEPTION, name));
        }
      } else if (code.equals("compiler.err.does.not.override.abstract")) {
        final Either<String, ?> msgEither = diag.getMessage();
        final String name =
            extractMissingMethodClassName(
                diag,
                analysis,
                msgEither != null && msgEither.isLeft() ? msgEither.getLeft() : null);
        if (name != null) {
          diag.setData(new DiagnosticPayload(DiagnosticPayload.Kind.MISSING_METHOD_IMPL, name));
        }
      }
    }
  }

  private static boolean notDuplicate(final Diagnostic d, final Set<String> seen) {
    final Either<String, Integer> codeEither = d.getCode();
    if (codeEither == null || !codeEither.isLeft()) {
      return true;
    }

    final String code = codeEither.getLeft();
    if (!code.startsWith("compiler.err.cant.resolve") || !(d.getData() instanceof String name)) {
      return true;
    }

    return seen.add("%s|%s|%s".formatted(d.getRange().getStart().getLine(), code, name));
  }

  private static DiagnosticPayload.Kind resolveKind(
      final Diagnostic diag, final AttributedFileAnalysis analysis) {
    if (analysis.tree() == null) {
      return DiagnosticPayload.Kind.TYPE_REF;
    }

    final long offset =
        SourceLocator.toOffset(
            analysis.tree(),
            diag.getRange().getStart().getLine(),
            diag.getRange().getStart().getCharacter());
    final TreePath path = SourceLocator.pathAt(analysis.trees(), analysis.tree(), offset);
    if (path == null || path.getParentPath() == null) {
      return DiagnosticPayload.Kind.TYPE_REF;
    }

    return switch (path.getParentPath().getLeaf().getKind()) {
      case VARIABLE -> {
        final var varTree = (VariableTree) path.getParentPath().getLeaf();
        yield varTree.getType() == path.getLeaf()
            ? DiagnosticPayload.Kind.TYPE_REF
            : DiagnosticPayload.Kind.VARIABLE_REF;
      }
      case METHOD,
          CLASS,
          INTERFACE,
          ENUM,
          RECORD,
          ANNOTATION_TYPE,
          PARAMETERIZED_TYPE,
          ANNOTATED_TYPE,
          ARRAY_TYPE,
          TYPE_CAST,
          TYPE_PARAMETER,
          INTERSECTION_TYPE,
          UNION_TYPE,
          EXTENDS_WILDCARD,
          SUPER_WILDCARD,
          NEW_CLASS,
          INSTANCE_OF ->
          DiagnosticPayload.Kind.TYPE_REF;
      default -> DiagnosticPayload.Kind.VARIABLE_REF;
    };
  }

  private static String extractExceptionName(final String message) {
    if (message == null) {
      return null;
    }

    final var prefix = "unreported exception ";
    final int start = message.indexOf(prefix);
    if (start < 0) {
      return null;
    }

    final int nameStart = start + prefix.length();
    final int end = message.indexOf(';', nameStart);
    if (end < 0) {
      return null;
    }

    return message.substring(nameStart, end).trim();
  }

  private static String extractMissingMethodClassName(
      final Diagnostic diag, final AttributedFileAnalysis analysis, final String message) {
    final String astName = missingMethodClassNameFromAst(diag, analysis);
    if (astName != null) {
      return astName;
    }

    if (message == null) {
      return null;
    }

    final var suffix = " is not abstract and does not override abstract method ";
    final int end = message.indexOf(suffix);
    return end > 0 ? message.substring(0, end).trim() : null;
  }

  private static String missingMethodClassNameFromAst(
      final Diagnostic diag, final AttributedFileAnalysis analysis) {
    if (analysis.tree() == null) {
      return null;
    }

    final TreePath path =
        CodeActionSupport.pathAt(
            analysis,
            diag.getRange().getStart().getLine(),
            diag.getRange().getStart().getCharacter());
    return missingMethodClassNameFromPath(path);
  }

  private static String missingMethodClassNameFromPath(final TreePath path) {
    if (path == null) {
      return null;
    }

    if (path.getLeaf() instanceof ClassTree classTree) {
      return classTree.getSimpleName().toString();
    }

    return missingMethodClassNameFromPath(path.getParentPath());
  }
}
