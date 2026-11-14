package io.github.aglibs.lathe.server.debug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.microsoft.java.debug.core.IEvaluatableBreakpoint;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.Value;
import io.github.aglibs.lathe.analysis.SourceAnalysisSession;
import io.github.aglibs.lathe.analysis.testing.TempSourceCompiler;
import io.github.aglibs.lathe.server.module.CompilationWorker;
import io.github.aglibs.lathe.server.module.TestWorkers;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

// The evaluation provider the debug adapter calls, against a real debuggee: it finds the frame's
// source, attributes the expression on the file's worker, and evaluates it, as for the debug
// console, a conditional breakpoint, and the Variables view's collection and map entries.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class LatheEvaluationProviderDebuggeeTest {

  private Debuggee debuggee;
  private CompilationWorker worker;
  private LatheEvaluationProvider provider;

  @BeforeAll
  void start(@TempDir final Path dir) throws Exception {
    debuggee = Debuggee.start(dir);
    worker = TestWorkers.over(new SourceAnalysisSession(new TempSourceCompiler()));
    provider =
        new LatheEvaluationProvider(
            new FrameSources(
                task -> CompletableFuture.runAsync(task, Runnable::run),
                file -> Optional.of(worker),
                List.of(debuggee.sourceRoot())));
  }

  @AfterAll
  void close() {
    if (worker != null) {
      TestWorkers.close(worker);
    }

    if (debuggee != null) {
      debuggee.close();
    }
  }

  @Test
  void evaluate_inTheFrame_answersOrSaysWhy() throws Exception {
    final var thread = debuggee.thread();

    assertThat(((IntegerValue) provider.evaluate("ten * 2", thread, 0).get()).value())
        .isEqualTo(20);
    assertThat(failure(provider.evaluate("1 / 0", thread, 0)))
        .isEqualTo("cannot evaluate '1 / 0': / by zero");
    assertThat(failure(provider.evaluate("missing + 1", thread, 0)))
        .isEqualTo("cannot resolve 'missing'");
  }

  @Test
  void evaluateForBreakpoint_condition_decidesWhetherItStops() throws Exception {
    final var thread = debuggee.thread();

    assertThat(
            ((BooleanValue) provider.evaluateForBreakpoint(breakpoint("ten > 5"), thread).get())
                .value())
        .isTrue();
    assertThat(
            ((BooleanValue) provider.evaluateForBreakpoint(breakpoint("ten > 50"), thread).get())
                .value())
        .isFalse();
    assertThat(failure(provider.evaluateForBreakpoint(breakpoint(""), thread)))
        .contains("only conditional breakpoints");
  }

  // The Variables view expands a collection or map through its members, evaluated on the object;
  // the immutable List.of and Map.of classes are not public, so a public supertype is named.
  @Test
  void evaluate_onACollectionOrMap_usesItsMembers() throws Exception {
    final var thread = debuggee.thread();
    final var names = (ObjectReference) debuggee.local("names");
    final var ages = (ObjectReference) debuggee.local("ages");

    assertThat(((IntegerValue) provider.evaluate("size()", names, thread).get()).value())
        .isEqualTo(2);
    assertThat(
            ((BooleanValue) provider.evaluate("containsKey(\"ann\")", ages, thread).get()).value())
        .isTrue();
  }

  private static IEvaluatableBreakpoint breakpoint(final String condition) {
    final var breakpoint = mock(IEvaluatableBreakpoint.class);
    when(breakpoint.getCondition()).thenReturn(condition);
    return breakpoint;
  }

  private static String failure(final CompletableFuture<Value> evaluation) {
    try {
      evaluation.get();
      throw new AssertionError("expected the evaluation to fail");
    } catch (final ExecutionException e) {
      return e.getCause().getMessage();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
