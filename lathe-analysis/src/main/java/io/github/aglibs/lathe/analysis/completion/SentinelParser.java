package io.github.aglibs.lathe.analysis.completion;

import com.sun.source.tree.*;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.analysis.CompilerTrees;
import io.github.aglibs.lathe.analysis.SourceParser;
import io.github.aglibs.lathe.core.CollectionUtil;
import io.github.aglibs.validcheck.ValidCheck;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;

final class SentinelParser {

  private static final Logger LOG = Logger.getLogger(SentinelParser.class.getName());
  // The element an annotation's shorthand argument (`@A(x)`) sets.
  static final String VALUE_ELEMENT = "value";

  private final SourceParser sourceParser;

  SentinelParser(final SourceParser sourceParser) {
    this.sourceParser = sourceParser;
  }

  ParsedSentinel parse(
      final SentinelInjectionResult injected,
      final int expectedLspLine,
      final int version,
      final String uri) {
    return sourceParser
        .parseContent(
            uri,
            injected.injectedContent(),
            (trees, cu) ->
                findSentinel(
                    trees,
                    cu,
                    new SentinelFinder().scan(cu, null),
                    injected,
                    expectedLspLine,
                    version,
                    false))
        .orElseGet(
            () -> ParsedSentinel.invalid(injected.prefix(), injected.receiverText(), version));
  }

  // A record body drops a member javac reads as an instance field, and the sentinel with it; as
  // the member stub's return type, or as the name stub's method name, it stays. Only a record's
  // member position counts, so a stub that landed in a block, or in another type where the first
  // parse failed for another reason, never answers.
  Optional<ParsedSentinel> parseMemberStub(
      final SentinelInjectionResult stub,
      final int expectedLspLine,
      final int version,
      final String uri) {
    return sourceParser.parseContent(
        uri,
        stub.injectedContent(),
        (trees, cu) -> memberStubSentinel(trees, cu, stub, expectedLspLine, version));
  }

  private static ParsedSentinel memberStubSentinel(
      final Trees trees,
      final CompilationUnitTree cu,
      final SentinelInjectionResult stub,
      final int expectedLspLine,
      final int version) {
    final TreePath returnTypePath = new SentinelFinder().scan(cu, null);
    final TreePath sentinelPath =
        returnTypePath != null ? returnTypePath : new MemberNameFinder().scan(cu, null);
    if (sentinelPath == null || !isRecordMemberStub(sentinelPath)) {
      return null;
    }

    final ParsedSentinel parsed =
        findSentinel(trees, cu, sentinelPath, stub, expectedLspLine, version, true);
    return parsed.valid() ? parsed : null;
  }

  private static boolean isRecordMemberStub(final TreePath sentinelPath) {
    final boolean nameStub = sentinelPath.getLeaf() instanceof MethodTree;
    final TreePath methodPath = nameStub ? sentinelPath : sentinelPath.getParentPath();
    if (methodPath == null || !(methodPath.getLeaf() instanceof final MethodTree method)) {
      return false;
    }

    final boolean stubbed =
        nameStub
            ? couldBeField(method)
            : method.getReturnType() == sentinelPath.getLeaf()
                && SentinelInjector.MEMBER_STUB_NAME.contentEquals(method.getName());
    return stubbed
        && methodPath.getParentPath() != null
        && methodPath.getParentPath().getLeaf() instanceof final ClassTree owner
        && owner.getKind() == Tree.Kind.RECORD;
  }

  // After `void` or a type parameter the name can only be a method's, for which a class body offers
  // no name either.
  private static boolean couldBeField(final MethodTree method) {
    return method.getReturnType() != null
        && method.getTypeParameters().isEmpty()
        && !(method.getReturnType() instanceof final PrimitiveTypeTree primitive
            && primitive.getPrimitiveTypeKind() == TypeKind.VOID);
  }

  // memberStub: the parse of a SentinelInjectionResult member stub, whose stub method holds the
  // sentinel at a member start and so is no enclosing method.
  private static ParsedSentinel findSentinel(
      final Trees trees,
      final CompilationUnitTree cu,
      final TreePath sentinelPath,
      final SentinelInjectionResult injected,
      final int expectedLspLine,
      final int version,
      final boolean memberStub) {
    if (sentinelPath == null) {
      LOG.fine(() -> "[sentinel-parse] sentinel not found in parse tree");
      if (cu.getModule() != null) {
        return ParsedSentinel.valid(
            injected, SentinelContext.MODULE_DIRECTIVE, -1, -1, ModuleDirectiveKind.NONE, version);
      }

      return ParsedSentinel.invalid(injected.prefix(), injected.receiverText(), version);
    }

    final var sourcePositions = trees.getSourcePositions();
    final Tree receiver =
        sentinelPath.getLeaf() instanceof final MemberSelectTree memberSelect
            ? memberSelect.getExpression()
            : sentinelPath.getLeaf() instanceof final MemberReferenceTree memberReference
                ? memberReference.getQualifierExpression()
                : null;
    final int receiverStartOffset =
        receiver == null ? -1 : (int) Math.max(-1, sourcePositions.getStartPosition(cu, receiver));
    final int receiverEndOffset =
        receiver == null ? -1 : (int) Math.max(-1, sourcePositions.getEndPosition(cu, receiver));

    final long startPos = sourcePositions.getEndPosition(cu, sentinelPath.getLeaf());
    final long javacLine = cu.getLineMap().getLineNumber(startPos);
    if (javacLine - 1 != expectedLspLine) {
      LOG.fine(
          () ->
              "[sentinel-parse] sentinel moved: expected lsp-line=%d javac-line=%d"
                  .formatted(expectedLspLine, javacLine));
      return ParsedSentinel.invalid(injected.prefix(), injected.receiverText(), version);
    }

    if (injected.prefix().isEmpty() && !injected.hasDot() && !injected.memberReference()) {
      final long sentinelStart = sourcePositions.getStartPosition(cu, sentinelPath.getLeaf());
      if (sentinelStart >= 0
          && expressionEndsBefore(sentinelStart, cu, sourcePositions, injected.injectedContent())) {
        return ParsedSentinel.invalid(injected.prefix(), injected.receiverText(), version);
      }
    }

    final var importTree = enclosingImport(sentinelPath);
    if (importTree != null) {
      final var parsed =
          ParsedSentinel.valid(
              injected,
              importTree.isStatic() ? SentinelContext.STATIC_IMPORT : SentinelContext.IMPORT,
              receiverStartOffset,
              receiverEndOffset,
              null,
              version);
      logValid(parsed);
      return parsed;
    }

    final var parentPath = sentinelPath.getParentPath();
    final Classification classified =
        parentPath != null
            ? classifySentinel(sentinelPath.getLeaf(), parentPath)
            : Classification.of(SentinelContext.SIMPLE_NAME);
    final Classification cls = correctExplicitDotRecovery(injected, classified);

    String enclosingClass = null;
    String enclosingMethod = null;
    for (final Tree node : sentinelPath) {
      if (node instanceof MethodTree m
          && enclosingMethod == null
          && !(memberStub
              && (SentinelInjector.MEMBER_STUB_NAME.contentEquals(m.getName())
                  || SentinelInjector.SENTINEL.contentEquals(m.getName())))) {
        enclosingMethod = m.getName().toString();
      } else if (node instanceof ClassTree c && !c.getSimpleName().isEmpty()) {
        // An anonymous class has no name: its enclosing named class is the one to complete in.
        enclosingClass = c.getSimpleName().toString();
        break;
      }
    }

    boolean enclosedByLoop = false;
    boolean enclosedBySwitchStatement = false;
    boolean enclosedBySwitchExpression = false;
    boolean inEqualityComparison = false;
    for (final Tree node : sentinelPath) {
      if (node instanceof MethodTree
          || node instanceof LambdaExpressionTree
          || node instanceof ClassTree) {
        break;
      }
      if (node instanceof ForLoopTree
          || node instanceof EnhancedForLoopTree
          || node instanceof WhileLoopTree
          || node instanceof DoWhileLoopTree) {
        enclosedByLoop = true;
      } else if (node instanceof SwitchExpressionTree) {
        enclosedBySwitchExpression = true;
      } else if (node instanceof SwitchTree) {
        enclosedBySwitchStatement = true;
      } else if (node instanceof final BinaryTree binary) {
        final var kind = binary.getKind();
        if (kind == Tree.Kind.EQUAL_TO || kind == Tree.Kind.NOT_EQUAL_TO) {
          inEqualityComparison = true;
        }
      }
    }

    if (enclosingClass == null && cu.getModule() != null) {
      final var keyword = findDirectiveKeyword(cu.getModule());
      final var parsed =
          ParsedSentinel.valid(
              injected,
              SentinelContext.MODULE_DIRECTIVE,
              receiverStartOffset,
              receiverEndOffset,
              keyword,
              version);
      logValid(parsed);
      return parsed;
    }

    // A catch parameter's name slot is a VariableTree whose parent is the CatchTree, distinguishing
    // it from a local declared inside the catch block.
    final boolean catchParameter =
        cls.context() == SentinelContext.VARIABLE_DECLARATION
            && parentPath != null
            && parentPath.getLeaf() instanceof CatchTree;

    // Only an explicit `static final` field is a constant; its name slot earns SCREAMING_SNAKE
    // suggestions. Implicit interface constants carry no keyword flags here, so they fall through.
    final boolean constantField =
        cls.context() == SentinelContext.VARIABLE_DECLARATION
            && sentinelPath.getLeaf() instanceof VariableTree v
            && v.getModifiers().getFlags().contains(Modifier.STATIC)
            && v.getModifiers().getFlags().contains(Modifier.FINAL);

    final List<String> typeVariables =
        acceptsTypeVariable(cls, sentinelPath) ? typeVariablesInScope(sentinelPath) : List.of();

    // Where the top-level class ends when the sentinel is a switch label's body, -1 otherwise, and
    // whether the sentinel is that body's statement.
    final Optional<TreePath> labelCase = labelBodyCase(sentinelPath);
    final int labelTopLevelEnd =
        labelCase.map(path -> topLevelEnd(path, cu, sourcePositions)).orElse(-1);
    final boolean labelBodyStatement =
        labelCase.filter(path -> labelBodyStatement(path, sentinelPath)).isPresent();

    final var parsed =
        ParsedSentinel.valid(
            injected,
            cls.context(),
            receiverStartOffset,
            receiverEndOffset,
            enclosingClass,
            enclosingMethod,
            cls.argIndex(),
            cls.enclosingReceiver(),
            cls.enclosingMethodName(),
            cls.lambdaParamIndex(),
            cls.declaredTypeText(),
            cls.declaredElementTypeName(),
            cls.annotationTypeText(),
            cls.typeReferenceRole(),
            enclosedByLoop,
            enclosedBySwitchStatement,
            enclosedBySwitchExpression,
            inEqualityComparison,
            cls.keywordSlot(),
            catchParameter,
            constantField,
            labelTopLevelEnd,
            labelBodyStatement,
            typeVariables,
            null,
            version);

    logValid(parsed);
    return parsed;
  }

  // The `case` or `default` whose body holds the sentinel, reached through its body, not its labels
  // or guard, before any lambda, call, parenthesis, conditional, array, block, method or class;
  // empty otherwise. A block javac's recovery made under an erroneous tree does not stop it: with
  // the switch block not closed, `case S s -> s.SENTINEL` and the next line read as a declaration
  // in such a block (CQ-0079).
  private static Optional<TreePath> labelBodyCase(final TreePath sentinelPath) {
    Tree child = sentinelPath.getLeaf();
    for (TreePath path = sentinelPath.getParentPath(); path != null; path = path.getParentPath()) {
      final Tree node = path.getLeaf();
      if (node instanceof final CaseTree caseTree) {
        final boolean body =
            child == caseTree.getBody()
                || caseTree.getStatements() != null && caseTree.getStatements().contains(child);
        return body ? Optional.of(path) : Optional.empty();
      }

      final boolean recoveryBlock =
          node instanceof BlockTree
              && path.getParentPath() != null
              && path.getParentPath().getLeaf() instanceof ErroneousTree;
      if (!recoveryBlock && stopsSwitchLabelBody(node)) {
        return Optional.empty();
      }

      child = node;
    }

    return Optional.empty();
  }

  // Whether the sentinel is itself a statement of the case: the expression of one of its expression
  // statements, reached through nothing but member selects and the erroneous tree javac wraps an
  // unfinished one in, so a value inside the statement (a for-each's iterable, an operand, a
  // `yield`) stays a value. In a switch statement javac reads an arrow's body as a statement
  // (`case "a" -> list.clear();`), and a colon case's code is statements in either kind of switch;
  // a switch expression's arrow body is the switch's value (CQ-0082).
  private static boolean labelBodyStatement(final TreePath casePath, final TreePath sentinelPath) {
    TreePath path = sentinelPath;
    while (path.getLeaf() instanceof IdentifierTree
        || path.getLeaf() instanceof MemberSelectTree
        || path.getLeaf() instanceof ErroneousTree) {
      path = path.getParentPath();
    }

    if (!(path.getLeaf() instanceof ExpressionStatementTree)
        || path.getParentPath().getLeaf() != casePath.getLeaf()) {
      return false;
    }

    return casePath.getParentPath().getLeaf() instanceof SwitchTree
        || ((CaseTree) casePath.getLeaf()).getCaseKind() == CaseTree.CaseKind.STATEMENT;
  }

  private static int topLevelEnd(
      final TreePath path, final CompilationUnitTree cu, final SourcePositions sourcePositions) {
    Tree outermost = null;
    for (TreePath up = path; up != null; up = up.getParentPath()) {
      if (up.getLeaf() instanceof ClassTree) {
        outermost = up.getLeaf();
      }
    }

    return outermost == null ? -1 : (int) sourcePositions.getEndPosition(cu, outermost);
  }

  private static boolean stopsSwitchLabelBody(final Tree node) {
    return node instanceof LambdaExpressionTree
        || node instanceof MethodInvocationTree
        || node instanceof NewClassTree
        || node instanceof ParenthesizedTree
        || node instanceof ConditionalExpressionTree
        || node instanceof ArrayAccessTree
        || node instanceof NewArrayTree
        || node instanceof BlockTree
        || node instanceof MethodTree
        || node instanceof ClassTree;
  }

  // A type variable is a type, never a value: it may stand where a type is written, or start a
  // statement that becomes a declaration (`T value`), but not before `.class` or a member, in
  // `instanceof`, after `new`, as a supertype, permitted subclass or annotation, or as a catch
  // parameter's type. Nor after `throws`, where only one bounded by Throwable may, which the parse
  // cannot tell.
  private static boolean acceptsTypeVariable(final Classification cls, final TreePath sentinel) {
    if (cls.context() == SentinelContext.TYPE_REFERENCE) {
      return switch (cls.typeReferenceRole()) {
        case ORDINARY ->
            !(catchParameterType(sentinel) || patternType(sentinel) || permittedSubclass(sentinel));
        case TYPE_ARGUMENT, ARRAY_TYPE_ARGUMENT -> true;
        case CONSTRUCTOR,
            CLASS_EXTENDS,
            CLASS_IMPLEMENTS,
            INTERFACE_EXTENDS,
            RECORD_IMPLEMENTS,
            THROWS,
            ANNOTATION ->
            false;
      };
    }

    if (cls.context() != SentinelContext.SIMPLE_NAME) {
      return false;
    }

    // A name alone is no statement: javac's recovery wraps it in an erroneous tree.
    TreePath statement = sentinel.getParentPath();
    if (statement != null && statement.getLeaf() instanceof ErroneousTree) {
      statement = statement.getParentPath();
    }

    // Only a statement of a block or of a `case 1:` group can become a declaration; a switch
    // rule's or an unbraced `if`'s body cannot.
    if (statement == null
        || !(statement.getLeaf() instanceof ExpressionStatementTree)
        || statement.getParentPath() == null) {
      return false;
    }

    final Tree container = statement.getParentPath().getLeaf();
    return container instanceof BlockTree
        || container instanceof final CaseTree group
            && group.getCaseKind() == CaseTree.CaseKind.STATEMENT;
  }

  // In `catch (A | B e)` each alternative's parent is the union type, not the parameter.
  private static boolean catchParameterType(final TreePath sentinel) {
    TreePath parameter = sentinel.getParentPath();
    if (parameter != null && parameter.getLeaf() instanceof UnionTypeTree) {
      parameter = parameter.getParentPath();
    }

    return parameter != null
        && parameter.getLeaf() instanceof VariableTree
        && parameter.getParentPath() != null
        && parameter.getParentPath().getLeaf() instanceof CatchTree;
  }

  private static boolean patternType(final TreePath sentinel) {
    final TreePath binding = sentinel.getParentPath();
    return binding != null
        && binding.getLeaf() instanceof VariableTree
        && binding.getParentPath() != null
        && binding.getParentPath().getLeaf() instanceof BindingPatternTree;
  }

  private static boolean permittedSubclass(final TreePath sentinel) {
    final TreePath owner = sentinel.getParentPath();
    return owner != null
        && owner.getLeaf() instanceof final ClassTree type
        && type.getPermitsClause().stream().anyMatch(t -> t == sentinel.getLeaf());
  }

  // Innermost first, so a method's or an inner class's type variable hides an outer one of the
  // same name. A static method, field or initializer hides its class's, and every field of an
  // interface is static; a type that is static, which every interface, enum and record is, sees
  // none declared around it.
  private static List<String> typeVariablesInScope(final TreePath sentinel) {
    final var names = new LinkedHashSet<String>();
    boolean classVariablesVisible = true;
    for (TreePath path = sentinel; path != null; path = path.getParentPath()) {
      final Tree node = path.getLeaf();
      if (node instanceof final MethodTree method) {
        addNames(method.getTypeParameters(), names);
        classVariablesVisible &= !method.getModifiers().getFlags().contains(Modifier.STATIC);
      } else if (node instanceof final VariableTree field) {
        classVariablesVisible &= !staticField(field, path.getParentPath());
      } else if (node instanceof final BlockTree block) {
        classVariablesVisible &= !block.isStatic();
      } else if (node instanceof final ClassTree type) {
        if (classVariablesVisible) {
          addNames(type.getTypeParameters(), names);
        }

        if (!seesEnclosingTypeVariables(type, path.getParentPath())) {
          break;
        }
      }
    }

    return List.copyOf(names);
  }

  // A member start in an interface (`T|` before `get();`) parses as a field with no name; a field
  // whose type is a type variable is never legal there, so it is a method's return type, which is
  // not static.
  private static boolean staticField(final VariableTree field, final TreePath owner) {
    return field.getModifiers().getFlags().contains(Modifier.STATIC)
        || SourceVersion.isIdentifier(field.getName())
            && owner != null
            && owner.getLeaf() instanceof final ClassTree type
            && (type.getKind() == Tree.Kind.INTERFACE
                || type.getKind() == Tree.Kind.ANNOTATION_TYPE);
  }

  private static void addNames(
      final List<? extends TypeParameterTree> typeParameters, final LinkedHashSet<String> names) {
    for (final TypeParameterTree typeParameter : typeParameters) {
      names.add(typeParameter.getName().toString());
    }
  }

  // An inner class (a member class not static, a local or an anonymous class) sees the type
  // variables around it; a member of an interface is static as an interface, enum or record is.
  private static boolean seesEnclosingTypeVariables(final ClassTree type, final TreePath parent) {
    return type.getKind() == Tree.Kind.CLASS
        && !type.getModifiers().getFlags().contains(Modifier.STATIC)
        && parent != null
        && !(parent.getLeaf() instanceof CompilationUnitTree)
        && !(parent.getLeaf() instanceof final ClassTree owner
            && (owner.getKind() == Tree.Kind.INTERFACE
                || owner.getKind() == Tree.Kind.ANNOTATION_TYPE));
  }

  private static void logValid(final ParsedSentinel parsed) {
    LOG.fine(
        () ->
            "[sentinel-parse] valid ctx=%s prefix=|%s| receiver=|%s|"
                .formatted(parsed.sentinelContext(), parsed.prefix(), parsed.receiverText()));
  }

  private static Classification correctExplicitDotRecovery(
      final SentinelInjectionResult injected, final Classification classified) {
    if (injected.hasDot()
        && injected.receiverText() != null
        && classified.context() == SentinelContext.SIMPLE_NAME) {
      return Classification.of(SentinelContext.MEMBER_ACCESS);
    }

    return classified;
  }

  private static boolean expressionEndsBefore(
      final long sentinelStart,
      final CompilationUnitTree cu,
      final SourcePositions positions,
      final String source) {
    final var found = new java.util.concurrent.atomic.AtomicBoolean();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitMethodInvocation(final MethodInvocationTree node, final Void unused) {
        checkEnd(node);
        return super.visitMethodInvocation(node, unused);
      }

      @Override
      public Void visitArrayAccess(final ArrayAccessTree node, final Void unused) {
        checkEnd(node);
        return super.visitArrayAccess(node, unused);
      }

      private void checkEnd(final Tree node) {
        if (found.get()) {
          return;
        }

        final long end = positions.getEndPosition(cu, node);
        if (end <= 0 || end > sentinelStart) {
          return;
        }

        for (long i = end; i < sentinelStart; i++) {
          if (!Character.isWhitespace(source.charAt((int) i))) {
            return;
          }
        }

        found.set(true);
      }
    }.scan(cu, null);
    return found.get();
  }

  private static ImportTree enclosingImport(final TreePath path) {
    for (final Tree node : path) {
      if (node instanceof final ImportTree importTree) {
        return importTree;
      }
    }

    return null;
  }

  private record Classification(
      SentinelContext context,
      int argIndex,
      String enclosingReceiver,
      String enclosingMethodName,
      int lambdaParamIndex,
      String declaredTypeText,
      String declaredElementTypeName,
      String annotationTypeText,
      TypeReferenceRole typeReferenceRole,
      KeywordSlot keywordSlot) {

    Classification {
      ValidCheck.check()
          .notNull(context, "context")
          .notNull(typeReferenceRole, "typeReferenceRole")
          .notNull(keywordSlot, "keywordSlot")
          .validate();
    }

    static Classification of(final SentinelContext ctx) {
      return new Classification(
          ctx,
          -1,
          null,
          null,
          -1,
          null,
          null,
          null,
          TypeReferenceRole.ORDINARY,
          KeywordSlot.BLOCK_STATEMENT);
    }

    static Classification typeReference(final TypeReferenceRole role) {
      return new Classification(
          SentinelContext.TYPE_REFERENCE,
          -1,
          null,
          null,
          -1,
          null,
          null,
          null,
          role,
          KeywordSlot.BLOCK_STATEMENT);
    }

    static Classification annotation() {
      return new Classification(
          SentinelContext.ANNOTATION_CONTEXT,
          -1,
          null,
          null,
          -1,
          null,
          null,
          null,
          TypeReferenceRole.ANNOTATION,
          KeywordSlot.BLOCK_STATEMENT);
    }

    static Classification annotationArgument(final String annotationTypeText) {
      return new Classification(
          SentinelContext.ANNOTATION_ARGUMENT,
          -1,
          null,
          null,
          -1,
          null,
          null,
          annotationTypeText,
          TypeReferenceRole.ORDINARY,
          KeywordSlot.BLOCK_STATEMENT);
    }

    static Classification annotationArgumentValue(
        final String annotationTypeName, final String elementName) {
      return new Classification(
          SentinelContext.ANNOTATION_ARGUMENT_VALUE,
          -1,
          null,
          elementName, // enclosingMethodName repurposed: stores the element name
          -1,
          null,
          null,
          annotationTypeName,
          TypeReferenceRole.ORDINARY,
          KeywordSlot.BLOCK_STATEMENT);
    }

    static Classification expression() {
      return simpleName(KeywordSlot.VALUE);
    }

    static Classification simpleName(final KeywordSlot keywordSlot) {
      return new Classification(
          SentinelContext.SIMPLE_NAME,
          -1,
          null,
          null,
          -1,
          null,
          null,
          null,
          TypeReferenceRole.ORDINARY,
          keywordSlot);
    }
  }

  private static Classification classifySentinel(final Tree sentinel, final TreePath parentPath) {
    if (sentinel instanceof VariableTree v) {
      return classifyDeclaration(v.getType());
    }

    // The name stub's method: its name is the member's, its return type the member's type.
    if (sentinel instanceof final MethodTree method) {
      return classifyDeclaration(method.getReturnType());
    }

    if (sentinel instanceof MemberReferenceTree) {
      return Classification.of(SentinelContext.MEMBER_REFERENCE);
    }

    final Tree parent = parentPath.getLeaf();
    final boolean simpleName = !(sentinel instanceof MemberSelectTree);
    final String annotationArgumentType = annotationArgumentType(sentinel, parentPath);
    if (!simpleName && annotationArgumentType != null) {
      return Classification.of(SentinelContext.MEMBER_ACCESS);
    }

    final AnnotationValueInfo annotationValue = annotationArgumentValue(sentinel, parentPath);
    if (annotationValue != null) {
      return Classification.annotationArgumentValue(
          annotationValue.annotationType(), annotationValue.elementName());
    }

    if (annotationArgumentType != null) {
      return Classification.annotationArgument(annotationArgumentType);
    }

    final TypeReferenceRole inferredRole = inferTypeReferenceRole(sentinel, parentPath);

    if (inferredRole == TypeReferenceRole.ANNOTATION) {
      return Classification.annotation();
    }

    if (inferredRole != TypeReferenceRole.ORDINARY) {
      return Classification.typeReference(inferredRole);
    }

    // --- statement-level expression positions ---
    if (simpleName
        && (parent instanceof final ReturnTree returnTree && returnTree.getExpression() == sentinel
            || parent instanceof final ThrowTree throwTree && throwTree.getExpression() == sentinel
            || parent instanceof final VariableTree variable
                && variable.getInitializer() == sentinel
            || parent instanceof final NewArrayTree newArray
                && newArray.getInitializers() != null
                && newArray.getInitializers().contains(sentinel))) {
      return Classification.expression();
    }

    if (simpleName
        && parent instanceof final YieldTree yieldTree
        && yieldTree.getValue() == sentinel) {
      return Classification.simpleName(KeywordSlot.YIELD_VALUE);
    }

    // A switch expression's arrow body is the case's own body, the switch's value (CQ-0084).
    if (simpleName
        && parent instanceof final CaseTree caseTree
        && caseTree.getBody() == sentinel
        && parentPath.getParentPath().getLeaf() instanceof SwitchExpressionTree) {
      return Classification.simpleName(KeywordSlot.SWITCH_RULE_VALUE);
    }

    final Classification expressionStatement =
        simpleName ? expressionStatementClassification(parentPath) : null;
    if (expressionStatement != null) {
      return expressionStatement;
    }

    // --- expression-tree parents: sentinel is a sub-expression ---
    if (parent instanceof ConditionalExpressionTree
        || parent instanceof BinaryTree
        || parent instanceof UnaryTree
        || parent instanceof ParenthesizedTree
        || parent instanceof final InstanceOfTree instanceOf
            && instanceOf.getExpression() == sentinel
        || parent instanceof final TypeCastTree cast && cast.getExpression() == sentinel
        || parent instanceof final AssignmentTree assignment
            && assignment.getExpression() == sentinel
        || parent instanceof final CompoundAssignmentTree compound
            && compound.getExpression() == sentinel
        || parent instanceof final ArrayAccessTree access && access.getIndex() == sentinel
        || parent instanceof final NewArrayTree newArray
            && newArray.getDimensions().stream().anyMatch(d -> d == sentinel)) {
      return Classification.expression();
    }

    if (parent instanceof final MemberSelectTree m && m.getExpression() == sentinel) {
      if (isClassLiteral(m)) {
        return Classification.typeReference(inferredRole);
      }

      return simpleName
          ? Classification.expression()
          : Classification.of(SentinelContext.MEMBER_ACCESS);
    }

    // --- control-flow condition / selector positions ---
    if (parent instanceof final IfTree ifTree && ifTree.getCondition() == sentinel
        || parent instanceof final WhileLoopTree whileLoop && whileLoop.getCondition() == sentinel
        || parent instanceof final DoWhileLoopTree doWhile && doWhile.getCondition() == sentinel
        || parent instanceof final ForLoopTree forLoop && forLoop.getCondition() == sentinel
        || parent instanceof final EnhancedForLoopTree forEach
            && forEach.getExpression() == sentinel
        || parent instanceof final SynchronizedTree sync && sync.getExpression() == sentinel
        || parent instanceof final SwitchTree switchTree && switchTree.getExpression() == sentinel
        || parent instanceof final SwitchExpressionTree switchExpression
            && switchExpression.getExpression() == sentinel) {
      return Classification.expression();
    }

    // --- method-call / constructor / lambda / annotation: existing paths ---
    if (parent instanceof final MethodInvocationTree m
        && simpleName
        && m.getArguments().stream().anyMatch(a -> a == sentinel)) {
      return classifyMethodInvocation(sentinel, m);
    }

    if (parent instanceof final LambdaExpressionTree lambda) {
      return classifyLambda(sentinel, lambda);
    }

    if (parent instanceof final NewClassTree m && simpleName) {
      return classifyConstructorCall(sentinel, m);
    }

    if (parent instanceof AnnotationTree) {
      return Classification.annotation();
    }

    // --- type-reference positions ---
    if (parent instanceof final VariableTree variable && variable.getType() == sentinel
        || parent instanceof final MethodTree method && method.getReturnType() == sentinel
        || parent instanceof final TypeCastTree cast && cast.getType() == sentinel
        || parent instanceof ParameterizedTypeTree
        || parent instanceof ClassTree
        || parent instanceof ArrayTypeTree
        || parent instanceof WildcardTree
        || parent instanceof TypeParameterTree) {
      return Classification.typeReference(inferredRole);
    }

    if (CompilerTrees.isCaseLabel(parent, sentinel)) {
      return Classification.of(SentinelContext.CASE_LABEL);
    }

    return classifyDefault(sentinel);
  }

  private static Classification classifyDeclaration(final Tree type) {
    // Treat an erroneous type as absent so the position is not flagged as a typed name slot.
    final boolean realType = type != null && type.getKind() != Tree.Kind.ERRONEOUS;
    return new Classification(
        SentinelContext.VARIABLE_DECLARATION,
        -1,
        null,
        null,
        -1,
        realType ? simpleTypeName(type) : null,
        realType ? elementTypeName(type) : null,
        null,
        TypeReferenceRole.ORDINARY,
        KeywordSlot.BLOCK_STATEMENT);
  }

  // The declared type's simple name, taken from the AST so no type text is parsed: the base of a
  // generic type (List<String> -> List) and the last segment of a qualified one (java.util.List ->
  // List). Other shapes (primitives, arrays) keep their source form and are filtered downstream.
  private static String simpleTypeName(final Tree type) {
    if (type instanceof final IdentifierTree id) {
      return id.getName().toString();
    }

    if (type instanceof final ParameterizedTypeTree parameterized) {
      return simpleTypeName(parameterized.getType());
    }

    if (type instanceof final MemberSelectTree member) {
      return member.getIdentifier().toString();
    }

    return type.toString();
  }

  // The simple name of the last type argument of a generic type (Map<String, User> -> User), or
  // null for a non-generic type. The last argument is the element/value in the common containers.
  private static String elementTypeName(final Tree type) {
    if (type instanceof ParameterizedTypeTree parameterized
        && !parameterized.getTypeArguments().isEmpty()) {
      final var args = parameterized.getTypeArguments();
      return simpleTypeName(CollectionUtil.last(args));
    }

    return null;
  }

  private static Classification classifyMethodInvocation(
      final Tree sentinel, final MethodInvocationTree m) {
    final var args = m.getArguments();
    final int argIndex =
        IntStream.range(0, args.size()).filter(j -> args.get(j) == sentinel).findFirst().orElse(0);
    final String enclosingReceiver;
    final String enclosingMethodName;
    if (m.getMethodSelect() instanceof final MemberSelectTree sel) {
      enclosingReceiver = sel.getExpression().toString();
      enclosingMethodName = sel.getIdentifier().toString();
    } else {
      enclosingReceiver = null;
      enclosingMethodName = m.getMethodSelect().toString();
    }

    return new Classification(
        SentinelContext.ARGUMENT_POSITION,
        argIndex,
        enclosingReceiver,
        enclosingMethodName,
        -1,
        null,
        null,
        null,
        TypeReferenceRole.ORDINARY,
        KeywordSlot.BLOCK_STATEMENT);
  }

  private static Classification classifyLambda(
      final Tree sentinel, final LambdaExpressionTree lambda) {
    if (!(sentinel instanceof final MemberSelectTree sel)) {
      return new Classification(
          SentinelContext.LAMBDA_BODY,
          -1,
          null,
          null,
          -1,
          null,
          null,
          null,
          TypeReferenceRole.ORDINARY,
          KeywordSlot.BLOCK_STATEMENT);
    }

    final var params = lambda.getParameters();
    final var receiver = sel.getExpression().toString();
    final int lambdaParamIndex =
        IntStream.range(0, params.size())
            .filter(j -> params.get(j).getName().toString().equals(receiver))
            .findFirst()
            .orElse(-1);
    return new Classification(
        SentinelContext.LAMBDA_BODY,
        -1,
        null,
        null,
        lambdaParamIndex,
        null,
        null,
        null,
        TypeReferenceRole.ORDINARY,
        KeywordSlot.BLOCK_STATEMENT);
  }

  private static Classification classifyConstructorCall(
      final Tree sentinel, final NewClassTree newClass) {
    final var args = newClass.getArguments();
    final int argIndex =
        IntStream.range(0, args.size()).filter(j -> args.get(j) == sentinel).findFirst().orElse(-1);
    final String constructorClassName = argIndex >= 0 ? newClass.getIdentifier().toString() : null;
    return new Classification(
        SentinelContext.CONSTRUCTOR_CALL,
        argIndex,
        null,
        constructorClassName,
        -1,
        null,
        null,
        null,
        argIndex < 0 ? TypeReferenceRole.CONSTRUCTOR : TypeReferenceRole.ORDINARY,
        KeywordSlot.BLOCK_STATEMENT);
  }

  private static String annotationArgumentType(final Tree sentinel, final TreePath parentPath) {
    Tree child = sentinel;
    for (TreePath path = parentPath; path != null; path = path.getParentPath()) {
      final Tree parent = path.getLeaf();
      final Tree currentChild = child;

      if (parent instanceof final AnnotationTree annotationTree) {
        return annotationTree.getAnnotationType() == currentChild
            ? null
            : annotationTree.getAnnotationType().toString();
      }

      child = parent;
    }

    return null;
  }

  // elementName: null for a bare name, which may be `value()`'s value or another element's name.
  private record AnnotationValueInfo(String annotationType, String elementName) {}

  private static AnnotationValueInfo annotationArgumentValue(
      final Tree sentinel, final TreePath parentPath) {
    Tree child = sentinel;
    for (TreePath path = parentPath; path != null; path = path.getParentPath()) {
      final Tree parent = path.getLeaf();
      final Tree currentChild = child;

      if (parent instanceof final AssignmentTree assignmentTree
          && assignmentTree.getExpression() == currentChild) {
        for (TreePath p = path.getParentPath(); p != null; p = p.getParentPath()) {
          if (p.getLeaf() instanceof final AnnotationTree annotationTree) {
            return new AnnotationValueInfo(
                annotationTree.getAnnotationType().toString(),
                assignmentTree.getVariable().toString());
          }
        }
      }

      // A name that is the sole argument is the `value()` shorthand or the start of a named
      // element:
      // no element name, so both are offered (CQ-0081). Beside other arguments it can only start a
      // named one, an argument's name, not a value. One inside the argument (an array
      // initializer's) is `value()`'s.
      if (parent instanceof final AnnotationTree annotationTree
          && !(currentChild instanceof AssignmentTree)
          && annotationTree.getArguments().stream().anyMatch(arg -> arg == currentChild)) {
        if (currentChild == sentinel && annotationTree.getArguments().size() > 1) {
          return null;
        }

        return new AnnotationValueInfo(
            annotationTree.getAnnotationType().toString(),
            currentChild == sentinel ? null : VALUE_ELEMENT);
      }

      child = parent;
    }

    return null;
  }

  private static TypeReferenceRole inferTypeReferenceRole(
      final Tree sentinel, final TreePath parentPath) {
    Tree child = sentinel;
    // Only the type a thrown `new` constructs must be a Throwable: an argument, a receiver or a
    // call around it takes any value, so the walk tracks whether it is still on that type.
    boolean thrownNewType = false;
    boolean arrayElement = false;
    for (TreePath path = parentPath; path != null; path = path.getParentPath()) {
      final Tree parent = path.getLeaf();
      final Tree currentChild = child;

      if (parent instanceof final AnnotationTree annotationTree
          && annotationTree.getAnnotationType() == currentChild) {
        return TypeReferenceRole.ANNOTATION;
      }

      // A type argument (of a type, or written on a call, a `new` or a method reference:
      // `Collections.<T>emptyList()`), a wildcard's bound within it, and a type parameter's bound
      // take any reference type: the role of what they parameterize (a supertype in a header, a
      // declared or thrown exception) does not reach them, so Comparable<Self> completes. An
      // array's element type on the way there may also be primitive (List<int[]>).
      if (parent instanceof final ParameterizedTypeTree parameterized
              && parameterized.getTypeArguments().stream().anyMatch(t -> t == currentChild)
          || parent instanceof final MethodInvocationTree call
              && call.getTypeArguments().stream().anyMatch(t -> t == currentChild)
          || parent instanceof final NewClassTree creation
              && creation.getTypeArguments().stream().anyMatch(t -> t == currentChild)
          || parent instanceof final MemberReferenceTree reference
              && reference.getTypeArguments() != null
              && reference.getTypeArguments().stream().anyMatch(t -> t == currentChild)
          || parent instanceof final TypeParameterTree typeParameter
              && typeParameter.getBounds().stream().anyMatch(t -> t == currentChild)) {
        return arrayElement
            ? TypeReferenceRole.ARRAY_TYPE_ARGUMENT
            : TypeReferenceRole.TYPE_ARGUMENT;
      }

      if (parent instanceof final ArrayTypeTree array && array.getType() == currentChild) {
        arrayElement = true;
      }

      if (parent instanceof final MethodTree method
          && method.getThrows().stream().anyMatch(t -> t == currentChild)) {
        return TypeReferenceRole.THROWS;
      }

      if (parent instanceof final ThrowTree throwTree
          && throwTree.getExpression() == currentChild
          && thrownNewType) {
        return TypeReferenceRole.THROWS;
      }

      if (parent instanceof final ClassTree cls) {
        final TypeReferenceRole role = inferClassHeaderRole(currentChild, cls);
        if (role != TypeReferenceRole.ORDINARY) {
          return role;
        }
      }

      // The `new` itself may sit in parentheses, a conditional, or an erroneous tree javac's
      // recovery builds around `throw new` with no arguments yet.
      if (parent instanceof final NewClassTree newClass) {
        thrownNewType = newClass.getIdentifier() == currentChild;
      } else if (!(parent instanceof ParenthesizedTree
          || parent instanceof ConditionalExpressionTree
          || parent instanceof ErroneousTree)) {
        thrownNewType = false;
      }

      child = parent;
    }

    return TypeReferenceRole.ORDINARY;
  }

  private static boolean isClassLiteral(final MemberSelectTree memberSelect) {
    return "class".contentEquals(memberSelect.getIdentifier());
  }

  private static TypeReferenceRole inferClassHeaderRole(final Tree child, final ClassTree cls) {
    if (cls.getExtendsClause() == child) {
      return cls.getKind() == Tree.Kind.INTERFACE
          ? TypeReferenceRole.INTERFACE_EXTENDS
          : TypeReferenceRole.CLASS_EXTENDS;
    }

    if (cls.getImplementsClause().stream().anyMatch(t -> t == child)) {
      return cls.getKind() == Tree.Kind.RECORD
          ? TypeReferenceRole.RECORD_IMPLEMENTS
          : TypeReferenceRole.CLASS_IMPLEMENTS;
    }

    return TypeReferenceRole.ORDINARY;
  }

  // A name alone is no statement: javac wraps it in an erroneous tree inside an expression
  // statement. Where that statement is a switch statement's arrow body or a `for` loop's update,
  // only some statements stand there (CQ-0084); null elsewhere, a block's or a colon case's
  // statement among them.
  private static Classification expressionStatementClassification(final TreePath parentPath) {
    if (!(parentPath.getLeaf() instanceof ErroneousTree)) {
      return null;
    }

    final TreePath statementPath = parentPath.getParentPath();
    if (statementPath == null
        || statementPath.getParentPath() == null
        || !(statementPath.getLeaf() instanceof final ExpressionStatementTree statement)) {
      return null;
    }

    final Tree owner = statementPath.getParentPath().getLeaf();
    if (owner instanceof final CaseTree caseTree && caseTree.getBody() == statement) {
      return Classification.simpleName(KeywordSlot.SWITCH_RULE_STATEMENT);
    }

    if (owner instanceof final ForLoopTree forLoop && forLoop.getUpdate().contains(statement)) {
      return Classification.simpleName(KeywordSlot.EXPRESSION_STATEMENT);
    }

    return null;
  }

  private static Classification classifyDefault(final Tree sentinel) {
    return Classification.of(
        sentinel instanceof MemberSelectTree
            ? SentinelContext.MEMBER_ACCESS
            : SentinelContext.SIMPLE_NAME);
  }

  static ModuleDirectiveKind findDirectiveKeyword(final ModuleTree moduleTree) {
    if (containsSentinel(moduleTree.getName())) {
      return ModuleDirectiveKind.MODULE;
    }

    for (final var directive : moduleTree.getDirectives()) {
      switch (directive.getKind()) {
        case EXPORTS -> {
          if (containsSentinel(((ExportsTree) directive).getPackageName())) {
            return ModuleDirectiveKind.EXPORTS;
          }
        }
        case OPENS -> {
          if (containsSentinel(((OpensTree) directive).getPackageName())) {
            return ModuleDirectiveKind.OPENS;
          }
        }
        case REQUIRES -> {
          final var req = (RequiresTree) directive;
          if (containsSentinel(req.getModuleName())) {
            return req.isTransitive()
                ? ModuleDirectiveKind.REQUIRES_TRANSITIVE
                : req.isStatic()
                    ? ModuleDirectiveKind.REQUIRES_STATIC
                    : ModuleDirectiveKind.REQUIRES;
          }
        }
        case USES -> {
          if (containsSentinel(((UsesTree) directive).getServiceName())) {
            return ModuleDirectiveKind.USES;
          }
        }
        case PROVIDES -> {
          final var prov = (ProvidesTree) directive;
          if (containsSentinel(prov.getServiceName())) {
            return ModuleDirectiveKind.PROVIDES;
          }

          for (final ExpressionTree impl : prov.getImplementationNames()) {
            if (containsSentinel(impl)) {
              return ModuleDirectiveKind.WITH;
            }
          }
        }
        default -> {}
      }
    }

    return ModuleDirectiveKind.NONE;
  }

  private static boolean containsSentinel(final Tree tree) {
    return tree != null && tree.toString().contains(SentinelInjector.SENTINEL);
  }

  // The name stub's method, which SentinelFinder does not look for: a method's name is no tree.
  private static final class MemberNameFinder extends TreePathScanner<TreePath, Void> {

    @Override
    public TreePath visitMethod(final MethodTree node, final Void unused) {
      if (SentinelInjector.SENTINEL.contentEquals(node.getName())) {
        return getCurrentPath();
      }

      return super.visitMethod(node, unused);
    }

    @Override
    public TreePath reduce(final TreePath r1, final TreePath r2) {
      return r1 != null ? r1 : r2;
    }
  }

  private static final class SentinelFinder extends TreePathScanner<TreePath, Void> {

    @Override
    public TreePath visitVariable(final VariableTree node, final Void unused) {
      if (SentinelInjector.SENTINEL.equals(node.getName().toString())) {
        return getCurrentPath();
      }

      return super.visitVariable(node, unused);
    }

    @Override
    public TreePath visitMemberSelect(final MemberSelectTree node, final Void unused) {
      if (SentinelInjector.SENTINEL.equals(node.getIdentifier().toString())) {
        return getCurrentPath();
      }

      return super.visitMemberSelect(node, unused);
    }

    @Override
    public TreePath visitMemberReference(final MemberReferenceTree node, final Void unused) {
      if (SentinelInjector.SENTINEL.equals(node.getName().toString())) {
        return getCurrentPath();
      }

      return super.visitMemberReference(node, unused);
    }

    @Override
    public TreePath visitIdentifier(final IdentifierTree node, final Void unused) {
      if (SentinelInjector.SENTINEL.equals(node.getName().toString())) {
        return getCurrentPath();
      }

      return super.visitIdentifier(node, unused);
    }

    @Override
    public TreePath visitErroneous(final ErroneousTree node, final Void unused) {
      // javac wraps non-statement expressions (e.g. bare member access) in ErroneousTree;
      // scan inside so the sentinel is still reachable.
      return scan(node.getErrorTrees(), unused);
    }

    @Override
    public TreePath reduce(final TreePath r1, final TreePath r2) {
      return r1 != null ? r1 : r2;
    }
  }
}
