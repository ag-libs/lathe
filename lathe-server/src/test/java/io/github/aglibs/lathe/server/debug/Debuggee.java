package io.github.aglibs.lathe.server.debug;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.EventSet;
import io.github.aglibs.lathe.analysis.testing.TestJavac;
import io.github.aglibs.lathe.server.module.CompilationWorker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

// A small fixture program launched under JDI with the test JDK's own java and stopped at a
// breakpoint, for tests of what the debugger computes against a real suspended frame. Its source
// is written to a directory of its own, which serves as the debug session's source root.
final class Debuggee implements AutoCloseable {

  static final String SOURCE =
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

        static int counter = 5;

        int[] next() {
          calls++;
          return new int[3];
        }

        void run() {
          int ten = 10;
          long big = 9007199254740993L;
          long near = 9007199254740992L;
          int[] numbers = {4, 5, 6};
          char letter = 'q';
          byte small = 3;
          short medium = 300;
          float half = 0.5f;
          boolean yes = true;
          Integer boxed = 7;
          String none = null;
          Object holder = new Holder();
          java.util.List<String> names = java.util.List.of("a", "b");
          java.util.Map<String, Integer> ages = java.util.Map.of("ann", 30);
          System.out.println(ten);
        }

        public static void main(String[] args) {
          new Sample().run();
        }
      }

      class Holder {
        @Override
        public String toString() {
          return "holder";
        }
      }

      // Never loaded by the debuggee until an evaluation names it.
      class Unused {
        static int answer() {
          return 42;
        }
      }
      """;

  // The println line (1-based), where every local of run() is in scope.
  static final int LINE =
      (int) SOURCE.lines().takeWhile(line -> !line.contains("System.out.println")).count() + 1;
  private static final long EVENT_TIMEOUT_MS = 30_000;

  private final Path sourceRoot;
  private final VirtualMachine vm;
  private final ThreadReference thread;

  private Debuggee(final Path sourceRoot, final VirtualMachine vm, final ThreadReference thread) {
    this.sourceRoot = sourceRoot;
    this.vm = vm;
    this.thread = thread;
  }

  static Debuggee start(final Path dir) throws Exception {
    final Path source = Files.writeString(dir.resolve("Sample.java"), SOURCE);
    final Path classes = dir.resolve("classes");
    // -g: without the local variable table JDI cannot read a local.
    TestJavac.compileToDir(classes, List.of(), List.of("-g"), source);
    final VirtualMachine vm = launch(classes);
    return new Debuggee(dir, vm, runToBreakpoint(vm));
  }

  // The thread stopped at LINE; frame(0) is run()'s frame.
  ThreadReference thread() {
    return thread;
  }

  // The debug session's view of this program's source, routed to one worker as a module's would be.
  FrameSources frameSources(final CompilationWorker worker) {
    return new FrameSources(
        task -> CompletableFuture.runAsync(task, Runnable::run),
        file -> Optional.of(worker),
        List.of(sourceRoot));
  }

  // A local of run(), read from a fresh frame.
  Value local(final String name) throws Exception {
    final var frame = thread.frame(0);
    return frame.getValue(frame.visibleVariableByName(name));
  }

  @Override
  public void close() {
    vm.exit(0);
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
  private static ThreadReference runToBreakpoint(final VirtualMachine vm) throws Exception {
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
