package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

// Request-driven refactor: for a `final` instance field with no initializer, add a parameter of the
// field's type to every constructor and bind it (`this.field = field;`), generating a constructor
// when none exists. A blank `final` field must be assigned exactly once on every construction path,
// so a delegating constructor (`this(...)`) forwards the value instead of binding, and a
// constructor
// that already assigns the field is left untouched. The parameter reuses the field's declared-type
// source text, which is already resolvable in the file, so no import is ever needed.
final class AddConstructorParameterProvider {

  private static final Logger LOG =
      Logger.getLogger(AddConstructorParameterProvider.class.getName());

  List<Either<Command, CodeAction>> provide(
      final String uri, final Range range, final AttributedFileAnalysis analysis) {
    final CompilationUnitTree cu = analysis.tree();
    if (cu == null) {
      return List.of();
    }

    final var trees = analysis.trees();
    final TreePath fieldPath =
        CodeActionSupport.enclosingVariable(
            CodeActionSupport.pathAt(
                analysis, range.getStart().getLine(), range.getStart().getCharacter()));
    if (fieldPath == null) {
      return List.of();
    }

    final var fieldTree = (VariableTree) fieldPath.getLeaf();
    final Element fieldElement = trees.getElement(fieldPath);
    if (fieldElement == null || fieldElement.getKind() != ElementKind.FIELD) {
      return List.of();
    }

    final Set<Modifier> mods = fieldTree.getModifiers().getFlags();
    if (!mods.contains(Modifier.FINAL) || mods.contains(Modifier.STATIC)) {
      return List.of();
    }

    if (fieldTree.getInitializer() != null) {
      return List.of();
    }

    final TreePath classPath = CodeActionSupport.enclosingClass(fieldPath);
    if (classPath == null) {
      return List.of();
    }

    final var cls = (ClassTree) classPath.getLeaf();
    if (cls.getKind() != Tree.Kind.CLASS && cls.getKind() != Tree.Kind.ENUM) {
      return List.of();
    }

    final String source;
    try {
      source = cu.getSourceFile().getCharContent(false).toString();
    } catch (final IOException e) {
      LOG.log(Level.WARNING, e, () -> "[codeAction:addCtorParam] failed to read source");
      return List.of();
    }

    final var positions = trees.getSourcePositions();
    final Tree typeTree = fieldTree.getType();
    final long typeStart = positions.getStartPosition(cu, typeTree);
    final long typeEnd = positions.getEndPosition(cu, typeTree);
    if (typeStart < 0 || typeEnd < 0) {
      return List.of();
    }

    final String typeText = source.substring((int) typeStart, (int) typeEnd);
    final var name = fieldTree.getName().toString();

    final List<TextEdit> edits =
        buildEdits(cls, classPath, cu, positions, trees, source, typeText, name, fieldElement);
    if (edits.isEmpty()) {
      return List.of();
    }

    LOG.fine(() -> "[codeAction:addCtorParam] %s edits=%d".formatted(name, edits.size()));
    return List.of(
        ExtractionSupport.action(
            uri,
            "Add constructor parameter '%s'".formatted(name),
            CodeActionKind.RefactorRewrite,
            edits));
  }

  private static List<TextEdit> buildEdits(
      final ClassTree cls,
      final TreePath classPath,
      final CompilationUnitTree cu,
      final SourcePositions positions,
      final Trees trees,
      final String source,
      final String typeText,
      final String name,
      final Element fieldElement) {
    final long classStart = positions.getStartPosition(cu, cls);
    final int brace = source.indexOf('{', (int) classStart);
    if (classStart < 0 || brace < 0) {
      return List.of();
    }

    final List<MethodTree> constructors = explicitConstructors(cls, cu, positions, brace);

    if (constructors.isEmpty()) {
      if (cls.getKind() != Tree.Kind.CLASS) {
        return List.of();
      }

      final TextEdit generated =
          generateConstructor(cls, cu, positions, source, brace, typeText, name);
      return generated == null ? List.of() : List.of(generated);
    }

    final List<MethodTree> toEdit =
        constructors.stream()
            .filter(ctor -> !assignsField(new TreePath(classPath, ctor), fieldElement, trees))
            .toList();
    if (toEdit.isEmpty()) {
      return List.of();
    }

    // A parameter already named after the field would become a duplicate parameter; withhold the
    // whole action rather than produce one broken constructor.
    if (toEdit.stream().anyMatch(ctor -> hasParameterNamed(ctor, name))) {
      return List.of();
    }

    final List<List<TextEdit>> perConstructor =
        toEdit.stream()
            .map(ctor -> constructorEdits(ctor, cu, positions, source, typeText, name))
            .toList();
    if (perConstructor.stream().anyMatch(Objects::isNull)) {
      return List.of();
    }

    return perConstructor.stream().flatMap(List::stream).toList();
  }

  // The parameter edit paired with its binding — or the forwarded argument for a delegating
  // constructor — or null when either edit cannot be placed.
  private static List<TextEdit> constructorEdits(
      final MethodTree ctor,
      final CompilationUnitTree cu,
      final SourcePositions positions,
      final String source,
      final String typeText,
      final String name) {
    final TextEdit param = parameterEdit(ctor, cu, positions, source, typeText, name);
    final MethodInvocationTree delegate = delegatingCall(ctor, cu, positions);
    final TextEdit bind =
        delegate != null
            ? forwardArgumentEdit(delegate, cu, positions, name)
            : bindingEdit(ctor, cu, positions, source, name);
    return param == null || bind == null ? null : List.of(param, bind);
  }

  // Source constructors only. A constructor has a null return type; javac's synthesized default
  // constructor is positioned before the class body's `{` (a real member always starts after it),
  // so
  // the `start > brace` guard drops it — the same discriminator `CodeActionSupport.lastField` uses.
  private static List<MethodTree> explicitConstructors(
      final ClassTree cls,
      final CompilationUnitTree cu,
      final SourcePositions positions,
      final int brace) {
    return cls.getMembers().stream()
        .filter(MethodTree.class::isInstance)
        .map(MethodTree.class::cast)
        .filter(member -> member.getReturnType() == null)
        .filter(ctor -> positions.getStartPosition(cu, ctor) > brace)
        .toList();
  }

  private static boolean assignsField(
      final TreePath methodPath, final Element field, final Trees trees) {
    final var found = new AtomicBoolean(false);
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitAssignment(final AssignmentTree node, final Void unused) {
        final Element target = trees.getElement(new TreePath(getCurrentPath(), node.getVariable()));
        if (field.equals(target)) {
          found.set(true);
        }

        return super.visitAssignment(node, unused);
      }
    }.scan(methodPath, null);
    return found.get();
  }

  private static boolean hasParameterNamed(final MethodTree ctor, final String name) {
    return ctor.getParameters().stream().anyMatch(p -> p.getName().contentEquals(name));
  }

  // The `this(...)` call of a constructor that delegates to a sibling, or null when it does not
  // delegate. javac prepends a synthetic (position-less) `super()` to a non-delegating constructor,
  // so the delegation call is the first statement carrying a real source position.
  private static MethodInvocationTree delegatingCall(
      final MethodTree ctor, final CompilationUnitTree cu, final SourcePositions positions) {
    final StatementTree first = firstRealStatement(ctor.getBody(), cu, positions);
    if (first instanceof final ExpressionStatementTree stmt
        && stmt.getExpression() instanceof final MethodInvocationTree call
        && call.getMethodSelect() instanceof final IdentifierTree select
        && select.getName().contentEquals("this")) {
      return call;
    }

    return null;
  }

  private static TextEdit parameterEdit(
      final MethodTree ctor,
      final CompilationUnitTree cu,
      final SourcePositions positions,
      final String source,
      final String typeText,
      final String name) {
    final String param = "final %s %s".formatted(typeText, name);
    final List<? extends VariableTree> params = ctor.getParameters();
    if (!params.isEmpty()) {
      final long end = positions.getEndPosition(cu, params.getLast());
      if (end < 0) {
        return null;
      }

      final var pos = SourceLocator.offsetToPosition(cu, end);
      return new TextEdit(new Range(pos, pos), ", %s".formatted(param));
    }

    final long methodStart = positions.getStartPosition(cu, ctor);
    final int open = source.indexOf('(', (int) methodStart);
    if (methodStart < 0 || open < 0) {
      return null;
    }

    final var pos = SourceLocator.offsetToPosition(cu, open + 1);
    return new TextEdit(new Range(pos, pos), param);
  }

  private static TextEdit bindingEdit(
      final MethodTree ctor,
      final CompilationUnitTree cu,
      final SourcePositions positions,
      final String source,
      final String name) {
    final BlockTree body = ctor.getBody();
    if (body == null) {
      return null;
    }

    final String bind = "this.%s = %s;".formatted(name, name);
    final StatementTree last = lastRealStatement(body, cu, positions);
    if (last != null) {
      final long end = positions.getEndPosition(cu, last);
      final long start = positions.getStartPosition(cu, last);
      final String indent = CodeActionSupport.lineIndent(source, (int) start);
      final var pos = SourceLocator.offsetToPosition(cu, end);
      return new TextEdit(new Range(pos, pos), "\n%s%s".formatted(indent, bind));
    }

    // Empty user body (only javac's synthetic super()): replace the inter-brace whitespace with the
    // single indented binding, so the result is `{\n  this.x = x;\n}` rather than a blank line.
    final long methodStart = positions.getStartPosition(cu, ctor);
    final int brace = source.indexOf('{', (int) methodStart);
    final long bodyEnd = positions.getEndPosition(cu, body);
    if (methodStart < 0 || brace < 0 || bodyEnd < 0) {
      return null;
    }

    final String indent = CodeActionSupport.lineIndent(source, (int) methodStart);
    final var open = SourceLocator.offsetToPosition(cu, brace + 1);
    final var close = SourceLocator.offsetToPosition(cu, bodyEnd - 1);
    return new TextEdit(new Range(open, close), "\n%s  %s\n%s".formatted(indent, bind, indent));
  }

  // The last statement carrying a real source position, skipping javac's synthetic super()/this()
  // (which report NOPOS); null when the body has no source statements.
  private static StatementTree lastRealStatement(
      final BlockTree body, final CompilationUnitTree cu, final SourcePositions positions) {
    return body.getStatements().stream()
        .filter(statement -> positions.getEndPosition(cu, statement) >= 0)
        .max(Comparator.comparingLong(statement -> positions.getEndPosition(cu, statement)))
        .orElse(null);
  }

  private static StatementTree firstRealStatement(
      final BlockTree body, final CompilationUnitTree cu, final SourcePositions positions) {
    if (body == null) {
      return null;
    }

    return body.getStatements().stream()
        .filter(statement -> positions.getStartPosition(cu, statement) >= 0)
        .min(Comparator.comparingLong(statement -> positions.getStartPosition(cu, statement)))
        .orElse(null);
  }

  private static TextEdit forwardArgumentEdit(
      final MethodInvocationTree call,
      final CompilationUnitTree cu,
      final SourcePositions positions,
      final String name) {
    final List<? extends ExpressionTree> args = call.getArguments();
    if (!args.isEmpty()) {
      final long end = positions.getEndPosition(cu, args.getLast());
      if (end < 0) {
        return null;
      }

      final var pos = SourceLocator.offsetToPosition(cu, end);
      return new TextEdit(new Range(pos, pos), ", %s".formatted(name));
    }

    final long callEnd = positions.getEndPosition(cu, call);
    if (callEnd < 0) {
      return null;
    }

    final var pos = SourceLocator.offsetToPosition(cu, callEnd - 1);
    return new TextEdit(new Range(pos, pos), name);
  }

  // Generates a constructor binding the field, placed just after the last field so it joins the
  // field block. The field under the caret is itself a field, so a last field always exists.
  private static TextEdit generateConstructor(
      final ClassTree cls,
      final CompilationUnitTree cu,
      final SourcePositions positions,
      final String source,
      final int brace,
      final String typeText,
      final String name) {
    final long classStart = positions.getStartPosition(cu, cls);
    final var className = cls.getSimpleName().toString();
    final VariableTree lastField = CodeActionSupport.lastField(cls, cu, positions, brace);
    if (lastField == null) {
      final String classIndent = CodeActionSupport.lineIndent(source, (int) classStart);
      final var pos = SourceLocator.offsetToPosition(cu, brace + 1);
      return new TextEdit(
          new Range(pos, pos),
          "\n%s  %s(final %s %s) {\n%s    this.%s = %s;\n%s  }\n"
              .formatted(
                  classIndent, className, typeText, name, classIndent, name, name, classIndent));
    }

    final long fieldEnd = positions.getEndPosition(cu, lastField);
    final long fieldStart = positions.getStartPosition(cu, lastField);
    if (fieldEnd < 0 || fieldStart < 0) {
      return null;
    }

    final String indent = CodeActionSupport.lineIndent(source, (int) fieldStart);
    final var pos = SourceLocator.offsetToPosition(cu, fieldEnd);
    return new TextEdit(
        new Range(pos, pos),
        "\n\n%s%s(final %s %s) {\n%s  this.%s = %s;\n%s}"
            .formatted(indent, className, typeText, name, indent, name, name, indent));
  }
}
