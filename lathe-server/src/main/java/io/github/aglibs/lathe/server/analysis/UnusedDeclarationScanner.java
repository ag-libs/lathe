package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.AssignmentTree;
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
import io.github.aglibs.validcheck.ValidCheck;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

final class UnusedDeclarationScanner extends TreePathScanner<Void, Void> {

  private static final Logger LOG = Logger.getLogger(UnusedDeclarationScanner.class.getName());

  private static final Set<String> EXCLUDED_FIELD_NAMES = Set.of("serialVersionUID");

  private enum Kind {
    LOCAL_VARIABLE("local variable"),
    PRIVATE_FIELD("private field"),
    PRIVATE_METHOD("private method");

    private final String label;

    Kind(final String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return label;
    }
  }

  private record Candidate(String name, long nodeStart, Kind kind) {
    Candidate {
      ValidCheck.check().notNull(name, "name").notNull(kind, "kind").validate();
    }
  }

  private final Trees trees;
  private final com.sun.source.tree.CompilationUnitTree cu;
  private final SourcePositions positions;
  private final String content;

  private boolean declarationPhase = true;

  private final Map<Element, Candidate> privateMethods = new LinkedHashMap<>();
  private final Map<Element, Candidate> privateFields = new LinkedHashMap<>();
  private final Map<Element, Candidate> localVars = new LinkedHashMap<>();

  // method reachability: BFS from directly-reached through private-to-private call edges
  private final Set<Element> directlyReached = new HashSet<>();
  private final Map<Element, Set<Element>> callEdges = new HashMap<>();

  // field/local: any reference anywhere counts
  private final Set<Element> referencedFields = new HashSet<>();
  private final Set<Element> referencedLocals = new HashSet<>();

  // targets of a suppressed pure-write assignment: used only to word the hint ("assigned but never
  // read") when such a declaration turns out to be unused; never counts as a reference.
  private final Set<Element> assignedTargets = new HashSet<>();

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
    scanner.declarationPhase = false;
    scanner.scan(analysis.tree(), null);
    return scanner.buildDiagnostics();
  }

  @Override
  public Void visitMethod(final MethodTree node, final Void v) {
    if (declarationPhase) {
      final var element = trees.getElement(getCurrentPath());
      if (element != null
          && element.getKind() == ElementKind.METHOD
          && element.getModifiers().contains(Modifier.PRIVATE)) {
        privateMethods.put(
            element, candidateFor(node, node.getName().toString(), Kind.PRIVATE_METHOD));
      }
    }
    return super.visitMethod(node, v);
  }

  @Override
  public Void visitVariable(final VariableTree node, final Void v) {
    if (declarationPhase) {
      final var element = trees.getElement(getCurrentPath());
      if (element != null) {
        final var parent = getCurrentPath().getParentPath().getLeaf();
        if (parent instanceof ClassTree classTree) {
          final boolean isRecordComponent =
              classTree.getKind() == Tree.Kind.RECORD
                  && !element.getModifiers().contains(Modifier.STATIC);
          if (!isRecordComponent
              && element.getModifiers().contains(Modifier.PRIVATE)
              && !EXCLUDED_FIELD_NAMES.contains(node.getName().toString())) {
            privateFields.put(
                element, candidateFor(node, node.getName().toString(), Kind.PRIVATE_FIELD));
          }
        } else if (!(parent instanceof MethodTree)) {
          localVars.put(
              element, candidateFor(node, node.getName().toString(), Kind.LOCAL_VARIABLE));
        }
      }
    }
    return super.visitVariable(node, v);
  }

  @Override
  public Void visitIdentifier(final IdentifierTree node, final Void v) {
    if (!declarationPhase) {
      markReference(trees.getElement(getCurrentPath()));
    }
    return super.visitIdentifier(node, v);
  }

  @Override
  public Void visitAssignment(final AssignmentTree node, final Void v) {
    if (declarationPhase || !isPureWriteTarget(node.getVariable())) {
      return super.visitAssignment(node, v);
    }

    // A bare `name = ...` or `this.field = ...` only writes the target, so its value is never
    // observed here; skip the target and scan only the right-hand side, whose reads still count.
    // Reads through the same variable (`x += 1`, `x++`, the `a` in `a.f = x`, the `arr` in
    // `arr[i] = x`) go through the default traversal and keep the declaration used.
    final var target = trees.getElement(new TreePath(getCurrentPath(), node.getVariable()));
    if (target != null) {
      assignedTargets.add(target);
    }

    scan(node.getExpression(), null);
    return null;
  }

  private static boolean isPureWriteTarget(final Tree target) {
    if (target instanceof IdentifierTree) {
      return true;
    }

    return target instanceof MemberSelectTree memberSelect
        && memberSelect.getExpression() instanceof IdentifierTree qualifier
        && qualifier.getName().contentEquals("this");
  }

  @Override
  public Void visitMemberSelect(final MemberSelectTree node, final Void v) {
    if (declarationPhase) {
      return super.visitMemberSelect(node, v);
    }
    scan(node.getExpression(), null);
    markReference(SourceLocator.elementAt(trees, getCurrentPath()));
    return null;
  }

  @Override
  public Void visitMemberReference(final MemberReferenceTree node, final Void v) {
    if (declarationPhase) {
      return super.visitMemberReference(node, v);
    }
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
    final var results = new ArrayList<Diagnostic>();
    collectUnused(privateMethods, reached, results);
    collectUnused(privateFields, referencedFields, results);
    collectUnused(localVars, referencedLocals, results);
    if (!results.isEmpty()) {
      final var uri = cu.getSourceFile().toUri();
      LOG.fine(() -> "[unused] %s total=%d".formatted(uri, results.size()));
    }
    return List.copyOf(results);
  }

  private void collectUnused(
      final Map<Element, Candidate> candidates,
      final Set<Element> used,
      final List<Diagnostic> results) {
    for (final var entry : candidates.entrySet()) {
      if (!used.contains(entry.getKey())) {
        unusedDiag(entry.getValue(), assignedTargets.contains(entry.getKey()))
            .ifPresent(results::add);
      }
    }
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

  private Optional<Diagnostic> unusedDiag(
      final Candidate candidate, final boolean assignedButNeverRead) {
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
    final String message =
        assignedButNeverRead
            ? "%s '%s' is assigned but never read".formatted(candidate.kind(), candidate.name())
            : "Unused %s '%s'".formatted(candidate.kind(), candidate.name());
    final var diag =
        new Diagnostic(new Range(start, end), message, DiagnosticSeverity.Hint, "lathe");
    diag.setCode("lathe.unused");
    diag.setTags(List.of(DiagnosticTag.Unnecessary));
    return Optional.of(diag);
  }

  private Candidate candidateFor(final Tree node, final String name, final Kind kind) {
    return new Candidate(name, positions.getStartPosition(cu, node), kind);
  }
}
