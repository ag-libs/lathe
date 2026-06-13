package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

final class UnusedDeclarationScanner extends TreePathScanner<Void, Void> {

  private static final Set<String> EXCLUDED_FIELD_NAMES = Set.of("serialVersionUID");

  private record Candidate(String name, long nodeStart) {}

  private final Trees trees;
  private final com.sun.source.tree.CompilationUnitTree cu;
  private final SourcePositions positions;
  private final String content;

  private final Map<Element, Candidate> privateMethods = new LinkedHashMap<>();
  private final Map<Element, Candidate> privateFields = new LinkedHashMap<>();
  private final Map<Element, Candidate> localVars = new LinkedHashMap<>();

  // method reachability: BFS from directly-reached through private-to-private call edges
  private final Set<Element> directlyReached = new HashSet<>();
  private final Map<Element, Set<Element>> callEdges = new HashMap<>();

  // field/local: any reference anywhere counts
  private final Set<Element> referencedFields = new HashSet<>();
  private final Set<Element> referencedLocals = new HashSet<>();

  private UnusedDeclarationScanner(final AttributedFileAnalysis analysis, final String content) {
    this.trees = analysis.trees();
    this.cu = analysis.tree();
    this.positions = trees.getSourcePositions();
    this.content = content;
  }

  static List<Diagnostic> scan(final AttributedFileAnalysis analysis, final String content) {
    if (analysis == null || analysis.tree() == null) {
      return List.of();
    }
    final var scanner = new UnusedDeclarationScanner(analysis, content);
    scanner.scan(analysis.tree(), null);
    return scanner.buildDiagnostics();
  }

  @Override
  public Void visitMethod(final MethodTree node, final Void v) {
    final var element = trees.getElement(getCurrentPath());
    if (element != null
        && element.getKind() == ElementKind.METHOD
        && element.getModifiers().contains(Modifier.PRIVATE)) {
      privateMethods.put(element, candidateFor(node, node.getName().toString()));
    }
    return super.visitMethod(node, v);
  }

  @Override
  public Void visitVariable(final VariableTree node, final Void v) {
    final var element = trees.getElement(getCurrentPath());
    if (element != null) {
      final var parent = getCurrentPath().getParentPath().getLeaf();
      if (parent instanceof ClassTree) {
        if (element.getModifiers().contains(Modifier.PRIVATE)
            && !EXCLUDED_FIELD_NAMES.contains(node.getName().toString())) {
          privateFields.put(element, candidateFor(node, node.getName().toString()));
        }
      } else if (!(parent instanceof MethodTree)) {
        localVars.put(element, candidateFor(node, node.getName().toString()));
      }
    }
    return super.visitVariable(node, v);
  }

  @Override
  public Void visitIdentifier(final IdentifierTree node, final Void v) {
    markReference(trees.getElement(getCurrentPath()));
    return super.visitIdentifier(node, v);
  }

  @Override
  public Void visitMemberSelect(final MemberSelectTree node, final Void v) {
    scan(node.getExpression(), null);
    markReference(SourceLocator.elementAt(trees, getCurrentPath()));
    return null;
  }

  @Override
  public Void visitMemberReference(final MemberReferenceTree node, final Void v) {
    scan(node.getQualifierExpression(), null);
    markReference(trees.getElement(getCurrentPath()));
    return null;
  }

  private void markReference(final Element element) {
    if (element == null) {
      return;
    }

    if (privateMethods.containsKey(element)) {
      final var enclosing = enclosingPrivateMethod();
      if (enclosing != null) {
        callEdges.computeIfAbsent(enclosing, k -> new HashSet<>()).add(element);
      } else {
        directlyReached.add(element);
      }
    } else if (privateFields.containsKey(element)) {
      referencedFields.add(element);
    } else if (localVars.containsKey(element)) {
      referencedLocals.add(element);
    }
  }

  private Element enclosingPrivateMethod() {
    TreePath path = getCurrentPath().getParentPath();
    while (path != null) {
      if (path.getLeaf() instanceof MethodTree) {
        final var element = trees.getElement(path);
        return (element != null && element.getModifiers().contains(Modifier.PRIVATE))
            ? element
            : null;
      }
      if (path.getLeaf() instanceof ClassTree) {
        return null;
      }
      path = path.getParentPath();
    }
    return null;
  }

  private List<Diagnostic> buildDiagnostics() {
    final var reached = reachableMethods();
    final List<Diagnostic> results = new ArrayList<>();
    collectUnused(privateMethods, reached, results);
    collectUnused(privateFields, referencedFields, results);
    collectUnused(localVars, referencedLocals, results);
    return List.copyOf(results);
  }

  private void collectUnused(
      final Map<Element, Candidate> candidates,
      final Set<Element> used,
      final List<Diagnostic> results) {
    candidates.forEach(
        (element, candidate) -> {
          if (!used.contains(element)) {
            unusedDiag(candidate).ifPresent(results::add);
          }
        });
  }

  private Set<Element> reachableMethods() {
    final var reached = new HashSet<>(directlyReached);
    final var queue = new ArrayDeque<>(directlyReached);
    while (!queue.isEmpty()) {
      for (final var callee : callEdges.getOrDefault(queue.poll(), Set.of())) {
        if (reached.add(callee)) {
          queue.add(callee);
        }
      }
    }
    return reached;
  }

  private Optional<Diagnostic> unusedDiag(final Candidate candidate) {
    if (candidate.nodeStart() < 0) {
      return Optional.empty();
    }
    final long nameOffset =
        SourceLocator.findIdentifierFrom(content, candidate.nodeStart(), candidate.name());
    if (nameOffset < 0) {
      return Optional.empty();
    }
    final var start = SourceLocator.offsetToPosition(cu, nameOffset);
    final var end = new Position(start.getLine(), start.getCharacter() + candidate.name().length());
    final var diag =
        new Diagnostic(new Range(start, end), "Unused", DiagnosticSeverity.Hint, "lathe");
    diag.setTags(List.of(DiagnosticTag.Unnecessary));
    return Optional.of(diag);
  }

  private Candidate candidateFor(final Tree node, final String name) {
    return new Candidate(name, positions.getStartPosition(cu, node));
  }
}
