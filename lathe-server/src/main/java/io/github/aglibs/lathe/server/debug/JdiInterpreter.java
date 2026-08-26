package io.github.aglibs.lathe.server.debug;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.server.analysis.AttributedExpression;
import java.util.List;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Stage 2 of read-only expression evaluation: walks the attributed expression tree bottom-up over
 * the suspended JDI frame, producing a {@link Value}. Reads (literals, locals, {@code this},
 * fields, array elements) run entirely against JDI mirrors; operators compute host-side using
 * javac's already-decided types, and {@code instanceof} against the live runtime type. No debuggee
 * code runs. Anything outside the read-only subset (method calls, assignment, lambdas, string
 * concatenation) raises {@link EvaluationException}.
 */
final class JdiInterpreter {

  private final AttributedExpression attr;
  private final StackFrame frame;
  private final VirtualMachine vm;

  JdiInterpreter(final AttributedExpression attr, final StackFrame frame) {
    this.attr = attr;
    this.frame = frame;
    this.vm = frame.virtualMachine();
  }

  Value evaluate() {
    return eval(attr.expression());
  }

  private Value eval(final TreePath path) {
    final Tree leaf = path.getLeaf();
    return switch (leaf) {
      case ParenthesizedTree p -> eval(child(path, p.getExpression()));
      case LiteralTree literal -> literal(literal);
      case IdentifierTree identifier -> identifier(path, identifier);
      case MemberSelectTree select -> memberSelect(path, select);
      case UnaryTree unary -> unary(path, unary);
      case BinaryTree binary -> binary(path, binary);
      case ArrayAccessTree array -> arrayAccess(path, array);
      case TypeCastTree cast -> cast(path, cast);
      case InstanceOfTree instanceOf -> instanceOf(path, instanceOf);
      default ->
          throw new EvaluationException("unsupported expression: " + leaf.getKind().toString());
    };
  }

  private Value literal(final LiteralTree literal) {
    final Object value = literal.getValue();
    return switch (value) {
      case null -> null;
      case Boolean b -> vm.mirrorOf(b);
      case Character c -> vm.mirrorOf(c);
      case Integer i -> vm.mirrorOf(i);
      case Long l -> vm.mirrorOf(l);
      case Float f -> vm.mirrorOf(f);
      case Double d -> vm.mirrorOf(d);
      case String s -> vm.mirrorOf(s);
      default -> throw new EvaluationException("unsupported literal: " + value);
    };
  }

  private Value memberSelect(final TreePath path, final MemberSelectTree select) {
    if ("length".contentEquals(select.getIdentifier())
        && eval(child(path, select.getExpression())) instanceof final ArrayReference array) {
      return vm.mirrorOf(array.length());
    }

    return readSymbol(path, select.getExpression());
  }

  private Value identifier(final TreePath path, final IdentifierTree identifier) {
    final var name = identifier.getName();
    if ("this".contentEquals(name) || "super".contentEquals(name)) {
      return thisObject(name.toString());
    }

    return readSymbol(path, null);
  }

  private ObjectReference thisObject(final String keyword) {
    final ObjectReference self = frame.thisObject();
    if (self == null) {
      throw new EvaluationException("'%s' is not available in a static context".formatted(keyword));
    }

    return self;
  }

  /** Reads a local/field/static identified by the resolved symbol at {@code path}. */
  private Value readSymbol(final TreePath path, final ExpressionTree receiver) {
    final JdiRef ref =
        SymbolToJdi.toRef(attr.trees().getElement(path), attr.types(), attr.elements())
            .orElseThrow(() -> new EvaluationException("cannot resolve: " + path.getLeaf()));
    return switch (ref) {
      case JdiRef.Local local -> readLocal(local.name());
      case JdiRef.Field field -> readField(path, field, receiver);
      case JdiRef.Type type ->
          throw new EvaluationException("'%s' is a type, not a value".formatted(type.binaryName()));
      case JdiRef.Method ignored ->
          throw new EvaluationException("method invocation is not supported yet (v2)");
    };
  }

  private Value readLocal(final String name) {
    try {
      final LocalVariable variable = frame.visibleVariableByName(name);
      if (variable == null) {
        throw new EvaluationException("local '%s' is not visible in this frame".formatted(name));
      }

      return frame.getValue(variable);
    } catch (final com.sun.jdi.AbsentInformationException e) {
      throw new EvaluationException("no local-variable table for this frame", e);
    }
  }

  private Value readField(
      final TreePath path, final JdiRef.Field field, final ExpressionTree recv) {
    final ReferenceType declaring = resolveType(field.declaringBinaryName());
    final Field jdiField = declaring.fieldByName(field.name());
    if (jdiField == null) {
      throw new EvaluationException(
          "no field '%s' on %s".formatted(field.name(), declaring.name()));
    }

    if (field.isStatic()) {
      return declaring.getValue(jdiField);
    }

    final Value target = recv != null ? eval(child(path, recv)) : frame.thisObject();
    if (!(target instanceof final ObjectReference object)) {
      throw new EvaluationException(
          "cannot read field '%s' of a non-object".formatted(field.name()));
    }

    return object.getValue(object.referenceType().fieldByName(field.name()));
  }

  private Value arrayAccess(final TreePath path, final ArrayAccessTree array) {
    if (!(eval(child(path, array.getExpression())) instanceof final ArrayReference reference)) {
      throw new EvaluationException("array access on a non-array");
    }

    return reference.getValue((int) asLong(eval(child(path, array.getIndex()))));
  }

  private Value cast(final TreePath path, final TypeCastTree castTree) {
    final Value value = eval(child(path, castTree.getExpression()));
    final TypeKind kind = typeOf(path).getKind();
    if (kind.isPrimitive() && value instanceof PrimitiveValue) {
      return mirrorNumeric(kind, asDouble(value), asLong(value));
    }

    return value;
  }

  private Value instanceOf(final TreePath path, final InstanceOfTree node) {
    if (!(eval(child(path, node.getExpression())) instanceof final ObjectReference object)) {
      return vm.mirrorOf(false);
    }

    final String target = targetBinaryName(child(path, node.getType()));
    return vm.mirrorOf(isSubtype(object.referenceType(), target));
  }

  /** True when {@code type} is, extends, or implements the class/interface named {@code target}. */
  private static boolean isSubtype(final ReferenceType type, final String target) {
    if (type.name().equals(target)) {
      return true;
    }

    if (type instanceof final ClassType classType) {
      for (ClassType superclass = classType.superclass();
          superclass != null;
          superclass = superclass.superclass()) {
        if (superclass.name().equals(target)) {
          return true;
        }
      }

      return classType.allInterfaces().stream().anyMatch(each -> each.name().equals(target));
    }

    return false;
  }

  private String targetBinaryName(final TreePath typePath) {
    final TypeMirror type = typeOf(typePath);
    if (type instanceof final DeclaredType declared
        && declared.asElement() instanceof final TypeElement element) {
      return attr.elements().getBinaryName(element).toString();
    }

    return type.toString();
  }

  private Value unary(final TreePath path, final UnaryTree unary) {
    final Value operand = eval(child(path, unary.getExpression()));
    return switch (unary.getKind()) {
      case LOGICAL_COMPLEMENT -> vm.mirrorOf(!asBoolean(operand));
      case UNARY_MINUS ->
          mirrorNumeric(typeOf(path).getKind(), -asDouble(operand), -asLong(operand));
      case UNARY_PLUS -> operand;
      case BITWISE_COMPLEMENT -> mirrorNumeric(typeOf(path).getKind(), 0, ~asLong(operand));
      default -> throw new EvaluationException("unsupported operator: " + unary.getKind());
    };
  }

  private Value binary(final TreePath path, final BinaryTree binary) {
    return switch (binary.getKind()) {
      case CONDITIONAL_AND ->
          vm.mirrorOf(
              asBoolean(eval(child(path, binary.getLeftOperand())))
                  && asBoolean(eval(child(path, binary.getRightOperand()))));
      case CONDITIONAL_OR ->
          vm.mirrorOf(
              asBoolean(eval(child(path, binary.getLeftOperand())))
                  || asBoolean(eval(child(path, binary.getRightOperand()))));
      default -> {
        final Value left = eval(child(path, binary.getLeftOperand()));
        final Value right = eval(child(path, binary.getRightOperand()));
        yield binaryOp(path, binary, left, right);
      }
    };
  }

  private Value binaryOp(
      final TreePath path, final BinaryTree binary, final Value left, final Value right) {
    return switch (binary.getKind()) {
      case EQUAL_TO -> vm.mirrorOf(equalValues(left, right));
      case NOT_EQUAL_TO -> vm.mirrorOf(!equalValues(left, right));
      case LESS_THAN -> vm.mirrorOf(asDouble(left) < asDouble(right));
      case LESS_THAN_EQUAL -> vm.mirrorOf(asDouble(left) <= asDouble(right));
      case GREATER_THAN -> vm.mirrorOf(asDouble(left) > asDouble(right));
      case GREATER_THAN_EQUAL -> vm.mirrorOf(asDouble(left) >= asDouble(right));
      case PLUS -> arithmetic(path, left, right, Double::sum, Long::sum);
      case MINUS -> arithmetic(path, left, right, (a, b) -> a - b, (a, b) -> a - b);
      case MULTIPLY -> arithmetic(path, left, right, (a, b) -> a * b, (a, b) -> a * b);
      case DIVIDE -> arithmetic(path, left, right, (a, b) -> a / b, (a, b) -> a / b);
      case REMAINDER -> arithmetic(path, left, right, (a, b) -> a % b, (a, b) -> a % b);
      case AND -> integerOp(path, left, right, (a, b) -> a & b);
      case OR -> integerOp(path, left, right, (a, b) -> a | b);
      case XOR -> integerOp(path, left, right, (a, b) -> a ^ b);
      case LEFT_SHIFT -> integerOp(path, left, right, (a, b) -> a << b);
      case RIGHT_SHIFT -> integerOp(path, left, right, (a, b) -> a >> b);
      case UNSIGNED_RIGHT_SHIFT -> integerOp(path, left, right, (a, b) -> a >>> b);
      default -> throw new EvaluationException("unsupported operator: " + binary.getKind());
    };
  }

  private Value arithmetic(
      final TreePath path,
      final Value left,
      final Value right,
      final java.util.function.DoubleBinaryOperator floating,
      final java.util.function.LongBinaryOperator integral) {
    if (left instanceof ObjectReference || right instanceof ObjectReference) {
      throw new EvaluationException("string concatenation is not supported yet (v2)");
    }

    final TypeKind kind = typeOf(path).getKind();
    return mirrorNumeric(
        kind,
        floating.applyAsDouble(asDouble(left), asDouble(right)),
        integral.applyAsLong(asLong(left), asLong(right)));
  }

  private Value integerOp(
      final TreePath path,
      final Value left,
      final Value right,
      final java.util.function.LongBinaryOperator op) {
    final TypeKind kind = typeOf(path).getKind();
    if (kind == TypeKind.BOOLEAN) {
      return vm.mirrorOf(op.applyAsLong(asBoolean(left) ? 1 : 0, asBoolean(right) ? 1 : 0) != 0);
    }

    return mirrorNumeric(kind, 0, op.applyAsLong(asLong(left), asLong(right)));
  }

  private boolean equalValues(final Value left, final Value right) {
    if (left instanceof PrimitiveValue && right instanceof PrimitiveValue) {
      if (typeOfValue(left) == TypeKind.BOOLEAN || typeOfValue(right) == TypeKind.BOOLEAN) {
        return asBoolean(left) == asBoolean(right);
      }

      return asDouble(left) == asDouble(right);
    }

    if (left == null || right == null) {
      return left == right;
    }

    return left.equals(right);
  }

  private ReferenceType resolveType(final String binaryName) {
    final List<ReferenceType> classes = vm.classesByName(binaryName);
    if (classes.isEmpty()) {
      throw new EvaluationException("class not loaded: " + binaryName);
    }

    return classes.getFirst();
  }

  private Value mirrorNumeric(final TypeKind kind, final double floating, final long integral) {
    return switch (kind) {
      case DOUBLE -> vm.mirrorOf(floating);
      case FLOAT -> vm.mirrorOf((float) floating);
      case LONG -> vm.mirrorOf(integral);
      case INT -> vm.mirrorOf((int) integral);
      case SHORT -> vm.mirrorOf((short) integral);
      case BYTE -> vm.mirrorOf((byte) integral);
      case CHAR -> vm.mirrorOf((char) integral);
      default -> throw new EvaluationException("non-numeric result type: " + kind);
    };
  }

  private static double asDouble(final Value value) {
    if (value instanceof final PrimitiveValue primitive) {
      return primitive.doubleValue();
    }

    throw new EvaluationException("expected a number");
  }

  private static long asLong(final Value value) {
    if (value instanceof final PrimitiveValue primitive) {
      return primitive.longValue();
    }

    throw new EvaluationException("expected an integer");
  }

  private static boolean asBoolean(final Value value) {
    if (value instanceof final BooleanValue bool) {
      return bool.booleanValue();
    }

    throw new EvaluationException("expected a boolean");
  }

  private TypeMirror typeOf(final TreePath path) {
    final TypeMirror type = attr.trees().getTypeMirror(path);
    if (type == null) {
      throw new EvaluationException("could not resolve the type of " + path.getLeaf());
    }

    return type;
  }

  private static TypeKind typeOfValue(final Value value) {
    return value instanceof BooleanValue ? TypeKind.BOOLEAN : TypeKind.DOUBLE;
  }

  private static TreePath child(final TreePath parent, final Tree node) {
    return new TreePath(parent, node);
  }
}
