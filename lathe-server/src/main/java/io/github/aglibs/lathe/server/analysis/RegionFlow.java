package io.github.aglibs.lathe.server.analysis;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.BreakTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ContinueTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.tree.YieldTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.core.CollectionUtil;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;

// How a region of a member (one expression, or consecutive statements of one block) uses the
// member's locals, judged for moving the region into a new method: the locals it reads with a
// value from before it (parameters), the ones it uses without one (the new method declares them),
// and the ones written or declared in it and used outside it (outputs). Conservative where javac's
// own flow analysis (internal API) would be exact: any use outside the region counts as later, so
// a read earlier in a loop's text is not missed. `returnsValue` says every path through the
// statements leaves the enclosing method with a value (or a throw), so the call is returned.
// `refusals` says why the region cannot move.
record RegionFlow(
    List<VariableElement> parameters,
    List<VariableElement> declaredHere,
    List<VariableElement> outputs,
    Set<TypeParameterElement> typeVariables,
    boolean returnsValue,
    List<String> refusals) {

  RegionFlow {
    ValidCheck.check()
        .notNull(parameters, "parameters")
        .notNull(declaredHere, "declaredHere")
        .notNull(outputs, "outputs")
        .notNull(typeVariables, "typeVariables")
        .notNull(refusals, "refusals")
        .validate();
    parameters = List.copyOf(parameters);
    declaredHere = List.copyOf(declaredHere);
    outputs = List.copyOf(outputs);
    typeVariables = Set.copyOf(typeVariables);
    refusals = List.copyOf(refusals);
  }

  private enum Access {
    READ,
    WRITE,
    READ_WRITE
  }

  private record Use(VariableElement variable, long position, Access access) {

    Use {
      ValidCheck.check().notNull(variable, "variable").notNull(access, "access").validate();
    }
  }

  static RegionFlow of(final TreePath member, final List<TreePath> region, final Trees trees) {
    final SourcePositions positions = trees.getSourcePositions();
    final var cu = member.getCompilationUnit();
    final long start = positions.getStartPosition(cu, region.get(0).getLeaf());
    final long end = positions.getEndPosition(cu, CollectionUtil.last(region).getLeaf());
    final LongPredicate inRegion = at -> at >= start && at < end;
    final Map<VariableElement, Long> declared = new HashMap<>();
    final Set<VariableElement> valueAtDeclaration = new HashSet<>();
    final Set<Tree> topLevel =
        region.stream().map(TreePath::getLeaf).collect(Collectors.toUnmodifiableSet());
    // For each local a statement of the region itself assigns (so on every path through it), where
    // the first such statement ends.
    final Map<VariableElement, Long> assignedAtTopLevel = new HashMap<>();
    final List<Use> uses = new ArrayList<>();
    final Set<TypeParameterElement> typeVariables = new HashSet<>();
    final List<String> refusals = new ArrayList<>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitVariable(final VariableTree tree, final Void unused) {
        if (trees.getElement(getCurrentPath()) instanceof final VariableElement variable) {
          declared.put(variable, positions.getStartPosition(cu, tree));
          if (tree.getInitializer() != null
              || variable.getKind() != ElementKind.LOCAL_VARIABLE
              || getCurrentPath().getParentPath().getLeaf() instanceof EnhancedForLoopTree) {
            valueAtDeclaration.add(variable);
          }

          if (tree.getInitializer() != null && topLevel.contains(tree)) {
            assignedAtTopLevel.merge(variable, positions.getEndPosition(cu, tree), Math::min);
          }
        }

        return super.visitVariable(tree, unused);
      }

      @Override
      public Void visitIdentifier(final IdentifierTree tree, final Void unused) {
        final Element element = trees.getElement(getCurrentPath());
        final long at = positions.getStartPosition(cu, tree);
        if (element instanceof final VariableElement variable && declared.containsKey(variable)) {
          final Access access = access(getCurrentPath());
          uses.add(new Use(variable, at, access));
          final Tree statement = getCurrentPath().getParentPath().getParentPath().getLeaf();
          if (access != Access.READ && topLevel.contains(statement)) {
            assignedAtTopLevel.merge(variable, positions.getEndPosition(cu, statement), Math::min);
          }
        }

        if (inRegion.test(at)) {
          noteInRegion(element);
        }

        return super.visitIdentifier(tree, unused);
      }

      @Override
      public Void visitAssignment(final AssignmentTree tree, final Void unused) {
        final Element target = trees.getElement(new TreePath(getCurrentPath(), tree.getVariable()));
        if (inRegion.test(positions.getStartPosition(cu, tree))
            && target != null
            && target.getKind() == ElementKind.FIELD
            && target.getModifiers().contains(Modifier.FINAL)) {
          refusals.add("assigns a final field");
        }

        return super.visitAssignment(tree, unused);
      }

      private void noteInRegion(final Element element) {
        if (element instanceof final TypeParameterElement typeVariable) {
          typeVariables.add(typeVariable);
        }

        if (element instanceof final TypeElement type
            && type.getNestingKind() == NestingKind.LOCAL
            && !declaredWithin(type, trees, inRegion)) {
          refusals.add("uses a local class");
        }
      }
    }.scan(member, null);

    final List<Use> usesInRegion =
        uses.stream().filter(use -> inRegion.test(use.position())).toList();
    final List<Use> outside = uses.stream().filter(use -> !inRegion.test(use.position())).toList();
    final Set<VariableElement> declaredInRegion =
        declared.entrySet().stream()
            .filter(entry -> inRegion.test(entry.getValue()))
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());

    // Every outer local the region uses, in order of first use: a parameter when the region reads
    // it before assigning it and it has a value before the region, else a local the new method
    // declares.
    final Set<VariableElement> used =
        usesInRegion.stream()
            .sorted(Comparator.comparingLong(Use::position))
            .map(Use::variable)
            .filter(variable -> !declaredInRegion.contains(variable))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    final List<VariableElement> parameters =
        used.stream()
            .filter(
                variable ->
                    anyUse(
                        usesInRegion,
                        variable,
                        use ->
                            use.access() != Access.WRITE
                                && use.position()
                                    < assignedAtTopLevel.getOrDefault(variable, Long.MAX_VALUE)))
            .filter(
                variable ->
                    valueAtDeclaration.contains(variable)
                        || anyUse(
                            uses,
                            variable,
                            use -> use.access() != Access.READ && use.position() < start))
            .toList();
    final List<VariableElement> declaredHere =
        used.stream().filter(variable -> !parameters.contains(variable)).toList();

    // Written or declared in the region, and used anywhere outside it.
    final List<VariableElement> outputs =
        Stream.concat(
                declaredInRegion.stream(),
                used.stream()
                    .filter(
                        variable ->
                            anyUse(usesInRegion, variable, use -> use.access() != Access.READ)))
            .distinct()
            .filter(
                variable ->
                    anyUse(
                        outside,
                        variable,
                        use -> declaredInRegion.contains(variable) || use.access() != Access.WRITE))
            .toList();

    // Assigned only in a branch or a loop, the old value may still be needed after the region.
    if (!assignedAtTopLevel.keySet().containsAll(outputs)) {
      refusals.add("a value flowing out is assigned conditionally");
    }

    final boolean statementForm = region.get(0).getLeaf() instanceof StatementTree;
    final boolean returnsValue = statementForm && returnsValue(region);
    if (statementForm) {
      refusals.addAll(controlRefusals(region, trees, returnsValue));
    }

    return new RegionFlow(parameters, declaredHere, outputs, typeVariables, returnsValue, refusals);
  }

  private static boolean anyUse(
      final List<Use> uses, final VariableElement variable, final Predicate<Use> condition) {
    return uses.stream().anyMatch(use -> use.variable() == variable && condition.test(use));
  }

  private static Access access(final TreePath identifier) {
    if (identifier.getParentPath().getLeaf() instanceof final AssignmentTree assignment
        && assignment.getVariable() == identifier.getLeaf()) {
      return Access.WRITE;
    }

    return ExtractionSupport.isAssignmentTarget(identifier) ? Access.READ_WRITE : Access.READ;
  }

  private static boolean declaredWithin(
      final TypeElement type, final Trees trees, final LongPredicate inRegion) {
    final TreePath path = trees.getPath(type);
    if (path == null) {
      return false;
    }

    return inRegion.test(
        trees.getSourcePositions().getStartPosition(path.getCompilationUnit(), path.getLeaf()));
  }

  // ── control flow of a statement region ───────────────────────────────────────────────────

  // Whether the statements cannot complete normally, directly in a method returning a value: then
  // every path ends in `return value;` or a throw, and `return extracted(…);` keeps both.
  private static boolean returnsValue(final List<TreePath> region) {
    return !completesNormally(CollectionUtil.last(region).getLeaf())
        && Stream.iterate(region.get(0), Objects::nonNull, TreePath::getParentPath)
            .map(TreePath::getLeaf)
            .filter(RegionFlow::isBodyBoundary)
            .findFirst()
            .filter(MethodTree.class::isInstance)
            .map(MethodTree.class::cast)
            .map(MethodTree::getReturnType)
            .filter(
                type ->
                    type != null
                        && !(type instanceof final PrimitiveTypeTree primitive
                            && primitive.getPrimitiveTypeKind() == TypeKind.VOID))
            .isPresent();
  }

  // Why moving the statements would change where control goes: a return (unless every path
  // returns a value), a break/continue/yield whose target lies outside them, a this(...)/super(...)
  // call (it must stay first), a rethrown catch parameter (Java's precise rethrow throws less than
  // its type says), or statements that never complete normally (the call would look like it
  // completes).
  private static List<String> controlRefusals(
      final List<TreePath> region, final Trees trees, final boolean returnsValue) {
    final Set<Tree> inside = new HashSet<>();
    region.forEach(
        root ->
            new TreePathScanner<Void, Void>() {
              @Override
              public Void scan(final Tree tree, final Void unused) {
                if (tree != null) {
                  inside.add(tree);
                }

                return super.scan(tree, unused);
              }
            }.scan(root, null));
    region.forEach(root -> inside.add(root.getLeaf()));

    final List<String> refusals = new ArrayList<>();
    region.forEach(
        root ->
            new TreePathScanner<Void, Void>() {
              @Override
              public Void visitLambdaExpression(
                  final LambdaExpressionTree tree, final Void unused) {
                return null;
              }

              @Override
              public Void visitClass(final ClassTree tree, final Void unused) {
                return null;
              }

              @Override
              public Void visitReturn(final ReturnTree tree, final Void unused) {
                if (!returnsValue) {
                  refusals.add("returns");
                }

                return null;
              }

              @Override
              public Void visitBreak(final BreakTree tree, final Void unused) {
                if (!inside.contains(target(getCurrentPath(), label(tree.getLabel()), false))) {
                  refusals.add("breaks out");
                }

                return null;
              }

              @Override
              public Void visitContinue(final ContinueTree tree, final Void unused) {
                if (!inside.contains(target(getCurrentPath(), label(tree.getLabel()), true))) {
                  refusals.add("continues out");
                }

                return null;
              }

              @Override
              public Void visitYield(final YieldTree tree, final Void unused) {
                if (!inside.contains(enclosingSwitchExpression(getCurrentPath()))) {
                  refusals.add("yields out");
                }

                return super.visitYield(tree, unused);
              }

              @Override
              public Void visitThrow(final ThrowTree tree, final Void unused) {
                if (trees.getElement(new TreePath(getCurrentPath(), tree.getExpression()))
                        instanceof final VariableElement thrown
                    && thrown.getKind() == ElementKind.EXCEPTION_PARAMETER) {
                  refusals.add("rethrows a catch parameter");
                }

                return super.visitThrow(tree, unused);
              }
            }.scan(root, null));

    if (region.stream()
        .anyMatch(statement -> ExtractionSupport.isConstructorCall(statement, trees))) {
      refusals.add("calls this(...) or super(...)");
    }

    if (!returnsValue && !completesNormally(CollectionUtil.last(region).getLeaf())) {
      refusals.add("never completes normally");
    }

    return refusals;
  }

  private static String label(final Name label) {
    return label == null ? null : label.toString();
  }

  // The statement a break or continue jumps to. Public javac API carries no target, so it is
  // found by walking up: the labelled statement, else the innermost loop (or switch, for break).
  private static Tree target(final TreePath from, final String label, final boolean loopsOnly) {
    for (TreePath path = from.getParentPath(); path != null; path = path.getParentPath()) {
      final Tree leaf = path.getLeaf();
      if (isBodyBoundary(leaf)) {
        return null;
      }

      if (label != null) {
        if (leaf instanceof final LabeledStatementTree labeled
            && labeled.getLabel().contentEquals(label)) {
          return labeled.getStatement();
        }

        continue;
      }

      if (isLoop(leaf) || (!loopsOnly && leaf instanceof SwitchTree)) {
        return leaf;
      }
    }
    return null;
  }

  // Where a return, break or continue stops looking for its target.
  private static boolean isBodyBoundary(final Tree tree) {
    return tree instanceof LambdaExpressionTree
        || tree instanceof MethodTree
        || tree instanceof ClassTree;
  }

  private static boolean isLoop(final Tree tree) {
    return tree instanceof WhileLoopTree
        || tree instanceof DoWhileLoopTree
        || tree instanceof ForLoopTree
        || tree instanceof EnhancedForLoopTree;
  }

  private static Tree enclosingSwitchExpression(final TreePath from) {
    return Stream.iterate(from, path -> path != null, TreePath::getParentPath)
        .map(TreePath::getLeaf)
        .filter(SwitchExpressionTree.class::isInstance)
        .findFirst()
        .orElse(null);
  }

  // Whether the statement can complete normally (JLS 14.22), approximated for the shapes that end
  // in a throw or a return: one, a block ending in one, an if/else or a try all of whose paths do.
  private static boolean completesNormally(final Tree statement) {
    if (statement instanceof ThrowTree || statement instanceof ReturnTree) {
      return false;
    }

    if (statement instanceof final BlockTree block) {
      return block.getStatements().isEmpty()
          || completesNormally(CollectionUtil.last(block.getStatements()));
    }

    if (statement instanceof final IfTree ifTree) {
      return ifTree.getElseStatement() == null
          || completesNormally(ifTree.getThenStatement())
          || completesNormally(ifTree.getElseStatement());
    }

    if (statement instanceof final TryTree tryTree) {
      return (tryTree.getFinallyBlock() == null || completesNormally(tryTree.getFinallyBlock()))
          && (completesNormally(tryTree.getBlock())
              || tryTree.getCatches().stream().anyMatch(c -> completesNormally(c.getBlock())));
    }

    return !(statement instanceof final LabeledStatementTree labeled)
        || completesNormally(labeled.getStatement());
  }
}
