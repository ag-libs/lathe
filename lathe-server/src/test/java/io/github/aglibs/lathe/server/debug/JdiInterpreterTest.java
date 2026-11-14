package io.github.aglibs.lathe.server.debug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.jdi.BooleanValue;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.EventSet;
import io.github.aglibs.lathe.analysis.SourceAnalysisSession;
import io.github.aglibs.lathe.analysis.testing.TempSourceCompiler;
import io.github.aglibs.lathe.analysis.testing.TestJavac;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

// The debug console's evaluator against a real debuggee: a JVM launched under JDI and stopped at a
// breakpoint, with the expression attributed against the same source, as the debug session does.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class JdiInterpreterTest {

  private static final String SOURCE =
      """
      class Base {
        int hidden = 1;

        String name() {
          return "base";
        }
      }

      class Sample extends Base {
        int hidden = 2;
        int calls;

        @Override
        String name() {
          return "sample";
        }

        int[] next() {
          calls++;
          return new int[3];
        }

        void run() {
          int ten = 10;
          long big = 9007199254740993L;
          long near = 9007199254740992L;
          System.out.println(ten);
        }

        public static void main(String[] args) {
          new Sample().run();
        }
      }
      """;

  // The println line (1-based), where every local of run() is in scope.
  private static final int LINE = 27;
  private static final long EVENT_TIMEOUT_MS = 30_000;

  private VirtualMachine vm;
  private ThreadReference thread;
  private SourceAnalysisSession session;

  @BeforeAll
  void stopAtBreakpoint(@TempDir final Path dir) throws Exception {
    final Path source = Files.writeString(dir.resolve("Sample.java"), SOURCE);
    final Path classes = dir.resolve("classes");
    // -g: without the local variable table JDI cannot read a local.
    TestJavac.compileToDir(classes, List.of(), List.of("-g"), source);
    vm = launch(classes);
    thread = runToBreakpoint();
    session = new SourceAnalysisSession(new TempSourceCompiler());
  }

  @AfterAll
  void close() {
    if (session != null) {
      session.close();
    }

    if (vm != null) {
      vm.exit(0);
    }
  }

  @Test
  void evaluate_arithmetic_asJavaComputesIt() throws Exception {
    assertThat(value("ten / 0.5")).isEqualTo(20.0);
    assertThat(value("7 % 0.5")).isEqualTo(0.0);
    assertThat(value("1 / 0.0")).isEqualTo(Double.POSITIVE_INFINITY);
    assertThat(value("\"a\" + 1 + 2.5")).isEqualTo("a12.5");
    assertThatThrownBy(() -> evaluate("1 / 0")).hasMessageContaining("/ by zero");
  }

  @Test
  void evaluate_longsBeyondDoublePrecision_compareExactly() throws Exception {
    assertThat(value("big == near")).isEqualTo(false);
    assertThat(value("big > near")).isEqualTo(true);
  }

  @Test
  void evaluate_superCallAndHiddenField_readTheSuperclasses() throws Exception {
    assertThat(value("name()")).isEqualTo("sample");
    assertThat(value("super.name()")).isEqualTo("base");
    assertThat(value("hidden")).isEqualTo(2);
    assertThat(value("((Base) this).hidden")).isEqualTo(1);
  }

  @Test
  void evaluate_calls_runOnceAndReachInterfaceStatics() throws Exception {
    final int before = (Integer) value("calls");

    assertThat(value("next().length")).isEqualTo(3);
    assertThat(value("calls")).isEqualTo(before + 1);
    assertThat(value("java.util.List.of(\"a\", \"b\").size()")).isEqualTo(2);
  }

  // The debuggee's value as a plain Java one, so an assertion reads like the expression's result.
  private Object value(final String expression) throws Exception {
    final Value value = evaluate(expression);
    if (value instanceof final IntegerValue integer) {
      return integer.value();
    }

    if (value instanceof final DoubleValue number) {
      return number.value();
    }

    if (value instanceof final BooleanValue bool) {
      return bool.value();
    }

    return value instanceof final StringReference string ? string.value() : value;
  }

  // A fresh frame per evaluation: a method call during one invalidates the frames taken before it.
  private Value evaluate(final String expression) throws Exception {
    final var attributed =
        session
            .files()
            .attributeExpression(TempSourceCompiler.TEST_URI, SOURCE, LINE, expression)
            .orElseThrow();
    return new JdiInterpreter(attributed, thread.frame(0), 0, JdiInterpreterTest::run, Map.of())
        .evaluate();
  }

  private static Value run(final JdiInterpreter.InvocationBody body) {
    try {
      return body.run();
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // The test JDK's own java, so the debuggee runs wherever the tests do.
  private static VirtualMachine launch(final Path classes) throws Exception {
    final LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
    final var arguments = connector.defaultArguments();
    arguments.get("home").setValue(System.getProperty("java.home"));
    arguments.get("options").setValue("-cp \"%s\"".formatted(classes));
    arguments.get("main").setValue("Sample");
    return connector.launch(arguments);
  }

  // Runs the launched VM until Sample is loaded, sets the breakpoint, and runs to it; the thread
  // then stays suspended for the evaluations.
  private ThreadReference runToBreakpoint() throws Exception {
    final var requests = vm.eventRequestManager();
    final var prepare = requests.createClassPrepareRequest();
    prepare.addClassFilter("Sample");
    prepare.enable();
    // Each event set is resumed once, the VM's start included: an extra resume could release the
    // class-prepare suspension before the breakpoint is set, and the debuggee would run past it.
    while (true) {
      final EventSet events = vm.eventQueue().remove(EVENT_TIMEOUT_MS);
      if (events == null) {
        throw new IllegalStateException("the debuggee never reached line %d".formatted(LINE));
      }

      for (final var event : events) {
        if (event instanceof final BreakpointEvent hit) {
          return hit.thread();
        }

        if (event instanceof final ClassPrepareEvent prepared) {
          requests
              .createBreakpointRequest(prepared.referenceType().locationsOfLine(LINE).get(0))
              .enable();
        }
      }

      events.resume();
    }
  }
}
