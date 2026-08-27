package io.github.aglibs.lathe.server.debug;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.ByteValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.ClassLoaderReference;
import com.sun.jdi.ClassObjectReference;
import com.sun.jdi.ClassType;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.Field;
import com.sun.jdi.FloatValue;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.LongValue;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ShortValue;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.TreePath;
import io.github.aglibs.lathe.server.analysis.AttributedExpression;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Stage 2 of read-only expression evaluation: walks the attributed expression tree bottom-up over
 * the suspended JDI frame, producing a {@link Value}. Reads (literals, locals, {@code this},
 * fields, array elements) run entirely against JDI mirrors; operators compute host-side using
 * javac's already-decided types, and {@code instanceof} against the live runtime type. Reads run no
 * debuggee code; method and constructor invocation and {@code String} concatenation (v2) do, on the
 * suspended thread under the provider's per-thread invocation lock. Referencing a static member of
 * a class the debuggee has not loaded yet force-loads and initialises it (running its static
 * initialiser), matching what the debuggee would see if it referenced the class itself. Assignment
 * and lambdas are not supported and raise {@link EvaluationException}.
 */
final class JdiInterpreter {

  private static final Logger LOG = Logger.getLogger(JdiInterpreter.class.getName());
  private static final int SINGLE_THREADED = ObjectReference.INVOKE_SINGLE_THREADED;

  private final AttributedExpression attr;
  private final VirtualMachine vm;
  private final GuardedInvoker invoker;
  // Captured up front, valid across a resume; the frame is NOT cached (see frame()).
  private final ThreadReference thread;
  private final int depth;
  // The stopped code's loader, captured up front for the same reason: it is the loader used to
  // force-load a cold class (null means bootstrap), and forcing it must give the type the same
  // visibility the frame has.
  private final ClassLoaderReference frameLoader;
  // Names bound to values that are not frame locals -- object-scoped evaluation binds the synthetic
  // receiver here so the interpreter resolves it to the given object rather than a frame slot.
  private final Map<String, Value> seed;

  JdiInterpreter(
      final AttributedExpression attr,
      final StackFrame frame,
      final int depth,
      final GuardedInvoker invoker,
      final Map<String, Value> seed) {
    this.attr = attr;
    this.vm = frame.virtualMachine();
    this.invoker = invoker;
    this.thread = frame.thread();
    this.depth = depth;
    this.frameLoader = frame.location().declaringType().classLoader();
    this.seed = Map.copyOf(seed);
  }

  /**
   * The current frame at this evaluation's depth, re-fetched on every access. A debuggee invocation
   * (a method/constructor call, or a cold-class force-load during symbol resolution) resumes the
   * thread and permanently invalidates every prior {@link StackFrame}, so a cached frame would
   * throw {@code InvalidStackFrameException} on the next read. The thread is suspended again
   * between invocations (and the provider holds its evaluation lock), so re-fetching always yields
   * a valid frame for the same suspended location.
   */
  private StackFrame frame() {
    try {
      return thread.frame(depth);
    } catch (final IncompatibleThreadStateException e) {
      throw new EvaluationException("thread is not suspended", e);
    }
  }

  /**
   * Runs a JDI method/constructor invocation under the owning thread's serialization lock (the
   * provider supplies it), so a debuggee call made while interpreting honours the same discipline
   * as the adapter's own invocations.
   */
  @FunctionalInterface
  interface GuardedInvoker {
    Value invoke(InvocationBody body);
  }

  @FunctionalInterface
  interface InvocationBody {
    Value run() throws Exception;
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
      case MethodInvocationTree call -> methodInvocation(path, call);
      case NewClassTree construction -> newInstance(path, construction);
      case ConditionalExpressionTree ternary -> ternary(path, ternary);
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
    final ObjectReference self = frame().thisObject();
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
      case JdiRef.Method ignored -> throw new EvaluationException("a method is not a value");
    };
  }

  private Value readLocal(final String name) {
    final Value bound = seed.get(name);
    if (bound != null) {
      return bound;
    }

    try {
      final StackFrame current = frame();
      final LocalVariable variable = current.visibleVariableByName(name);
      if (variable == null) {
        throw new EvaluationException("local '%s' is not visible in this frame".formatted(name));
      }

      return current.getValue(variable);
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

    final Value target = recv != null ? eval(child(path, recv)) : frame().thisObject();
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

  private Value methodInvocation(final TreePath path, final MethodInvocationTree call) {
    final JdiRef.Method ref = methodRef(path);
    final Method method = resolveMethod(ref);
    final List<Value> arguments = evalArguments(path, call.getArguments());
    if (ref.isStatic()) {
      final ClassType type = (ClassType) resolveType(ref.declaringBinaryName());
      return invoker.invoke(() -> type.invokeMethod(thread(), method, arguments, SINGLE_THREADED));
    }

    final ObjectReference receiver = invocationReceiver(path, call.getMethodSelect());
    return invoker.invoke(
        () -> receiver.invokeMethod(thread(), method, arguments, SINGLE_THREADED));
  }

  private Value newInstance(final TreePath path, final NewClassTree construction) {
    final JdiRef.Method ref = methodRef(path);
    final ClassType type = (ClassType) resolveType(ref.declaringBinaryName());
    final Method constructor = resolveMethod(ref);
    final List<Value> arguments = evalArguments(path, construction.getArguments());
    return invoker.invoke(
        () -> type.newInstance(thread(), constructor, arguments, SINGLE_THREADED));
  }

  private JdiRef.Method methodRef(final TreePath path) {
    if (SymbolToJdi.toRef(attr.trees().getElement(path), attr.types(), attr.elements())
            .orElseThrow(() -> new EvaluationException("cannot resolve call: " + path.getLeaf()))
        instanceof final JdiRef.Method method) {
      return method;
    }

    throw new EvaluationException("not a method: " + path.getLeaf());
  }

  private Method resolveMethod(final JdiRef.Method ref) {
    final ReferenceType declaring = resolveType(ref.declaringBinaryName());
    return declaring.methodsByName(ref.name(), ref.jvmSignature()).stream()
        .findFirst()
        .orElseThrow(
            () ->
                new EvaluationException(
                    "no method %s%s on %s"
                        .formatted(ref.name(), ref.jvmSignature(), declaring.name())));
  }

  private List<Value> evalArguments(
      final TreePath path, final List<? extends ExpressionTree> arguments) {
    return arguments.stream().map(argument -> eval(child(path, argument))).toList();
  }

  private ObjectReference invocationReceiver(final TreePath path, final ExpressionTree select) {
    if (select instanceof final MemberSelectTree member) {
      final Value target = eval(child(path, member.getExpression()));
      if (target == null) {
        throw new EvaluationException("cannot call '%s' on null".formatted(member.getIdentifier()));
      }

      if (!(target instanceof final ObjectReference object)) {
        throw new EvaluationException("cannot invoke a method on a non-object");
      }

      return object;
    }

    return thisObject("this");
  }

  private ThreadReference thread() {
    return thread;
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
      case LEFT_SHIFT -> shift(path, left, right, (a, b) -> a << b, (a, b) -> a << b);
      case RIGHT_SHIFT -> shift(path, left, right, (a, b) -> a >> b, (a, b) -> a >> b);
      case UNSIGNED_RIGHT_SHIFT -> shift(path, left, right, (a, b) -> a >>> b, (a, b) -> a >>> b);
      default -> throw new EvaluationException("unsupported operator: " + binary.getKind());
    };
  }

  private Value ternary(final TreePath path, final ConditionalExpressionTree ternary) {
    return asBoolean(eval(child(path, ternary.getCondition())))
        ? eval(child(path, ternary.getTrueExpression()))
        : eval(child(path, ternary.getFalseExpression()));
  }

  // Shifts are width-sensitive: the shift distance is masked to 5 bits for int and 6 for long, and
  // >>> zero-fills at the operand width -- so an int shift must be computed as int, not widened.
  private Value shift(
      final TreePath path,
      final Value left,
      final Value right,
      final java.util.function.IntBinaryOperator intOp,
      final java.util.function.LongBinaryOperator longOp) {
    if (typeOf(path).getKind() == TypeKind.LONG) {
      return vm.mirrorOf(longOp.applyAsLong(asLong(left), asLong(right)));
    }

    return vm.mirrorOf(intOp.applyAsInt((int) asLong(left), (int) asLong(right)));
  }

  private Value arithmetic(
      final TreePath path,
      final Value left,
      final Value right,
      final java.util.function.DoubleBinaryOperator floating,
      final java.util.function.LongBinaryOperator integral) {
    final TypeMirror type = typeOf(path);
    if ("java.lang.String".equals(type.toString())) {
      return vm.mirrorOf(stringValueOf(left) + stringValueOf(right));
    }

    return mirrorNumeric(
        type.getKind(),
        floating.applyAsDouble(asDouble(left), asDouble(right)),
        integral.applyAsLong(asLong(left), asLong(right)));
  }

  /** Renders a value the way {@code String +} would: primitives host-side, objects via toString. */
  private String stringValueOf(final Value value) {
    return switch (value) {
      case null -> "null";
      case StringReference string -> string.value();
      case BooleanValue b -> String.valueOf(b.value());
      case CharValue c -> String.valueOf(c.value());
      case ByteValue b -> String.valueOf(b.value());
      case ShortValue s -> String.valueOf(s.value());
      case IntegerValue i -> String.valueOf(i.value());
      case LongValue l -> String.valueOf(l.value());
      case FloatValue f -> String.valueOf(f.value());
      case DoubleValue d -> String.valueOf(d.value());
      case ObjectReference object -> invokeToString(object);
      default -> value.toString();
    };
  }

  private String invokeToString(final ObjectReference object) {
    final Method toString =
        object.referenceType().methodsByName("toString", "()Ljava/lang/String;").stream()
            .findFirst()
            .orElseThrow(
                () -> new EvaluationException("no toString on " + object.referenceType().name()));
    final Value rendered =
        invoker.invoke(() -> object.invokeMethod(thread(), toString, List.of(), SINGLE_THREADED));
    return rendered instanceof final StringReference string ? string.value() : "null";
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
    // A primitive on either side makes this a numeric/boolean comparison (javac unboxes the other).
    if (left instanceof PrimitiveValue || right instanceof PrimitiveValue) {
      final Value l = unbox(left);
      final Value r = unbox(right);
      if (l instanceof BooleanValue || r instanceof BooleanValue) {
        return asBoolean(l) == asBoolean(r);
      }

      return asDouble(l) == asDouble(r);
    }

    if (left == null || right == null) {
      return left == right;
    }

    return left.equals(right);
  }

  private ReferenceType resolveType(final String binaryName) {
    final List<ReferenceType> classes = vm.classesByName(binaryName);
    if (!classes.isEmpty()) {
      return classes.getFirst();
    }

    return forceLoad(binaryName);
  }

  /**
   * Loads a class the debuggee has not touched yet by invoking {@code Class.forName(name, true,
   * loader)} on the suspended thread -- the only way JDI can trigger a load. Initialising ({@code
   * true}) runs the class's static initialiser, matching the JVM semantics the debuggee would see
   * if it referenced the class itself; a static-field read needs it. Uses the stopped frame's
   * loader so the cold class resolves with the same visibility.
   */
  private ReferenceType forceLoad(final String binaryName) {
    final ClassType classClass = (ClassType) resolveType("java.lang.Class");
    final Method forName =
        classClass
            .methodsByName(
                "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;")
            .stream()
            .findFirst()
            .orElseThrow(() -> new EvaluationException("Class.forName is unavailable"));
    // Arrays.asList, not List.of: frameLoader is null for the bootstrap loader, which JDI accepts.
    final List<Value> arguments =
        Arrays.asList(vm.mirrorOf(binaryName), vm.mirrorOf(true), frameLoader);
    final Value loaded =
        invoker.invoke(
            () -> classClass.invokeMethod(thread(), forName, arguments, SINGLE_THREADED));
    if (loaded instanceof final ClassObjectReference reflected) {
      LOG.fine(() -> "[eval] force-loaded %s".formatted(binaryName));
      return reflected.reflectedType();
    }

    throw new EvaluationException("class not loaded: " + binaryName);
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

  private static final Set<String> BOXED =
      Set.of(
          "java.lang.Boolean",
          "java.lang.Byte",
          "java.lang.Character",
          "java.lang.Short",
          "java.lang.Integer",
          "java.lang.Long",
          "java.lang.Float",
          "java.lang.Double");

  /**
   * Unwraps a boxed primitive to its {@code value} field so operators work on {@code Integer} etc.
   */
  private static Value unbox(final Value value) {
    if (value instanceof final ObjectReference object
        && BOXED.contains(object.referenceType().name())) {
      return object.getValue(object.referenceType().fieldByName("value"));
    }

    return value;
  }

  private static double asDouble(final Value value) {
    if (unbox(value) instanceof final PrimitiveValue primitive) {
      return primitive.doubleValue();
    }

    throw new EvaluationException("expected a number");
  }

  private static long asLong(final Value value) {
    if (unbox(value) instanceof final PrimitiveValue primitive) {
      return primitive.longValue();
    }

    throw new EvaluationException("expected an integer");
  }

  private static boolean asBoolean(final Value value) {
    if (unbox(value) instanceof final BooleanValue bool) {
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

  private static TreePath child(final TreePath parent, final Tree node) {
    return new TreePath(parent, node);
  }
}
