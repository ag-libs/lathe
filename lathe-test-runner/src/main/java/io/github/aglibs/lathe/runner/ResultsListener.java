package io.github.aglibs.lathe.runner;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.ValueWrapper;

/**
 * Captures every method-level test outcome and writes one NDJSON {@link TestRecord} line per test
 * to a sink file. A container gets a line only when it fails itself: a class whose class-level
 * setup ({@code @BeforeAll}) broke (the line is the class's), or a parameterized method whose
 * arguments couldn't be produced (the line is the method's). Engines are ignored. A failing static
 * initializer is a method's failure: JUnit initializes the class when it creates the test instance.
 *
 * <p>Sink I/O never aborts the test run: any failure is reported once to {@code err} and the sink
 * is abandoned, leaving the process exit code (owned by the summary listener) untouched.
 */
final class ResultsListener implements TestExecutionListener {

  private static final String STATUS_PASSED = "passed";
  private static final String STATUS_FAILED = "failed";
  private static final String STATUS_SKIPPED = "skipped";
  // Mirror LatheFlags.TEST_STARTED_MARKER / TEST_FINISHED_MARKER by value: the runner can't depend
  // on
  // lathe-core. A drift-guard test pins them equal.
  static final String TEST_STARTED_MARKER = "\u0001lathe:test-started\t";
  static final String TEST_FINISHED_MARKER = "\u0001lathe:test-finished\t";
  // Where a failure's stack leaves the test's own code for the machinery that called it.
  private static final List<String> MACHINERY =
      List.of(
          "jdk.internal.reflect.",
          "java.lang.reflect.",
          "org.junit.platform.",
          "org.junit.jupiter.engine.");

  private final Path sink;
  private final PrintStream stdout;
  private final PrintStream err;
  private final Map<String, Long> startNanos = new ConcurrentHashMap<>();
  private Writer out;

  ResultsListener(final Path sink, final PrintStream stdout, final PrintStream err) {
    this.sink = sink;
    this.stdout = stdout;
    this.err = err;
  }

  @Override
  public void testPlanExecutionStarted(final TestPlan testPlan) {
    try {
      out =
          Files.newBufferedWriter(
              sink,
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING,
              StandardOpenOption.WRITE);
    } catch (final IOException e) {
      warn("open", e);
    }
  }

  @Override
  public void testPlanExecutionFinished(final TestPlan testPlan) {
    if (out == null) {
      return;
    }

    try {
      out.close();
    } catch (final IOException e) {
      warn("close", e);
    } finally {
      out = null;
    }
  }

  // System.nanoTime, not lathe-core's Stopwatch: the runner rides the user's test classpath and
  // can't depend on lathe-core.
  @Override
  public void executionStarted(final TestIdentifier id) {
    startNanos.put(id.getUniqueId(), System.nanoTime());
    mark(TEST_STARTED_MARKER, id);
  }

  @Override
  public void executionFinished(final TestIdentifier id, final TestExecutionResult result) {
    mark(TEST_FINISHED_MARKER, id);
    final Long start = startNanos.remove(id.getUniqueId());
    final long durationMs = start == null ? -1 : (System.nanoTime() - start) / 1_000_000;
    final boolean failedItself = result.getStatus() != TestExecutionResult.Status.SUCCESSFUL;
    final TestSource source = id.getSource().orElse(null);
    if (source instanceof final MethodSource method && (!id.isContainer() || failedItself)) {
      write(
          record(
              method.getClassName(),
              method.getMethodName(),
              parameterTypes(method),
              id,
              result,
              durationMs));
      return;
    }

    if (source instanceof final ClassSource type && id.isContainer() && failedItself) {
      write(record(type.getClassName(), "", "", id, result, durationMs));
    }
  }

  @Override
  public void executionSkipped(final TestIdentifier id, final String reason) {
    if (id.isContainer() || !(id.getSource().orElse(null) instanceof final MethodSource method)) {
      return;
    }

    write(
        new TestRecord(
            method.getClassName(),
            method.getMethodName(),
            parameterTypes(method),
            STATUS_SKIPPED,
            "",
            -1,
            -1,
            id.getDisplayName(),
            null,
            null,
            List.of()));
  }

  // On the stream the test's own output goes to, so the server reads them in the order they
  // happened
  // and can tell which test printed each line.
  private void mark(final String marker, final TestIdentifier id) {
    if (id.isContainer() || !(id.getSource().orElse(null) instanceof final MethodSource method)) {
      return;
    }

    stdout.println(
        "%s%s\t%s\t%s"
            .formatted(
                marker, method.getClassName(), method.getMethodName(), parameterTypes(method)));
    stdout.flush();
  }

  private void write(final TestRecord record) {
    if (out == null) {
      return;
    }

    try {
      out.write(toJson(record));
      out.write('\n');
      out.flush();
    } catch (final IOException e) {
      warn("write", e);
      out = null;
    }
  }

  private void warn(final String op, final IOException e) {
    err.printf("[results] sink %s %s failed: %s%n", sink, op, e.getMessage());
  }

  private static TestRecord record(
      final String className,
      final String methodName,
      final String parameterTypes,
      final TestIdentifier id,
      final TestExecutionResult result,
      final long durationMs) {
    final Throwable throwable = result.getThrowable().orElse(null);
    // Values for a diff only when the failure carries both, never parsed out of its message.
    final AssertionFailedError comparison =
        throwable instanceof final AssertionFailedError failed
                && failed.isExpectedDefined()
                && failed.isActualDefined()
            ? failed
            : null;
    return new TestRecord(
        className,
        methodName,
        parameterTypes,
        statusOf(result.getStatus()),
        throwable == null ? "" : String.valueOf(throwable),
        failureLine(throwable, className),
        durationMs,
        id.getDisplayName(),
        comparison == null ? null : text(comparison.getExpected()),
        comparison == null ? null : text(comparison.getActual()),
        stackTrace(throwable));
  }

  // Text as it is: JUnit's own representation quotes it. Anything else (an array, an object) as
  // JUnit represents it.
  private static String text(final ValueWrapper value) {
    return value.getValue() instanceof final CharSequence chars
        ? chars.toString()
        : value.getStringRepresentation();
  }

  private static String parameterTypes(final MethodSource method) {
    final String params = method.getMethodParameterTypes();
    return params == null ? "" : params;
  }

  /** The failure's frames down to where the test framework called into the test's own code. */
  static List<TestRecord.Frame> stackTrace(final Throwable throwable) {
    if (throwable == null) {
      return List.of();
    }

    final var frames = new ArrayList<TestRecord.Frame>();
    for (final StackTraceElement element : throwable.getStackTrace()) {
      if (MACHINERY.stream().anyMatch(element.getClassName()::startsWith)) {
        break;
      }

      final String fileName = element.getFileName();
      frames.add(
          new TestRecord.Frame(
              element.getClassName(),
              element.getMethodName(),
              fileName == null ? "" : fileName,
              element.getLineNumber()));
    }

    return List.copyOf(frames);
  }

  private static String statusOf(final TestExecutionResult.Status status) {
    return switch (status) {
      case SUCCESSFUL -> STATUS_PASSED;
      case FAILED -> STATUS_FAILED;
      case ABORTED -> STATUS_SKIPPED;
    };
  }

  /** Line of the topmost stack frame declared by the test's own class, or {@code -1} if none. */
  static int failureLine(final Throwable throwable, final String className) {
    if (throwable == null) {
      return -1;
    }

    for (final StackTraceElement element : throwable.getStackTrace()) {
      if (className.equals(element.getClassName()) && element.getLineNumber() > 0) {
        return element.getLineNumber();
      }
    }

    return -1;
  }

  static String toJson(final TestRecord record) {
    final var sb = new StringBuilder(128);
    sb.append('{');
    appendString(sb, "className", record.className());
    sb.append(',');
    appendString(sb, "methodName", record.methodName());
    sb.append(',');
    appendString(sb, "methodParameterTypes", record.methodParameterTypes());
    sb.append(',');
    appendString(sb, "status", record.status());
    sb.append(',');
    appendString(sb, "failureMessage", record.failureMessage());
    sb.append(",\"failureLine\":").append(record.failureLine());
    sb.append(",\"durationMs\":").append(record.durationMs());
    sb.append(',');
    appendString(sb, "displayName", record.displayName());
    appendOptionalString(sb, "expected", record.expected());
    appendOptionalString(sb, "actual", record.actual());
    sb.append(",\"stackTrace\":[");
    for (int i = 0; i < record.stackTrace().size(); i++) {
      if (i > 0) {
        sb.append(',');
      }

      appendFrame(sb, record.stackTrace().get(i));
    }

    sb.append("]}");
    return sb.toString();
  }

  private static void appendFrame(final StringBuilder sb, final TestRecord.Frame frame) {
    sb.append('{');
    appendString(sb, "className", frame.className());
    sb.append(',');
    appendString(sb, "methodName", frame.methodName());
    sb.append(',');
    appendString(sb, "fileName", frame.fileName());
    sb.append(",\"line\":").append(frame.line()).append('}');
  }

  // Left out entirely when absent, so the server reads it as missing rather than empty.
  private static void appendOptionalString(
      final StringBuilder sb, final String key, final String value) {
    if (value == null) {
      return;
    }

    sb.append(',');
    appendString(sb, key, value);
  }

  private static void appendString(final StringBuilder sb, final String key, final String value) {
    sb.append('"').append(key).append("\":\"");
    appendEscaped(sb, value);
    sb.append('"');
  }

  private static void appendEscaped(final StringBuilder sb, final String value) {
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        default -> appendPlainOrUnicode(sb, c);
      }
    }
  }

  private static void appendPlainOrUnicode(final StringBuilder sb, final char c) {
    if (c < 0x20) {
      sb.append("\\u%04x".formatted((int) c));
    } else {
      sb.append(c);
    }
  }
}
