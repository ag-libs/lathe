package io.github.aglibs.lathe.runner;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Captures every method-level test outcome and writes one NDJSON {@link TestRecord} line per test
 * to a sink file. Container identifiers (classes, engines) are ignored so only real per-method
 * results reach the sink.
 *
 * <p>Sink I/O never aborts the test run: any failure is reported once to {@code err} and the sink
 * is abandoned, leaving the process exit code (owned by the summary listener) untouched.
 */
final class ResultsListener implements TestExecutionListener {

  private static final String STATUS_PASSED = "passed";
  private static final String STATUS_FAILED = "failed";
  private static final String STATUS_SKIPPED = "skipped";

  private final Path sink;
  private final PrintStream err;
  private Writer out;

  ResultsListener(final Path sink, final PrintStream err) {
    this.sink = sink;
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

  @Override
  public void executionFinished(final TestIdentifier id, final TestExecutionResult result) {
    final MethodSource source = methodSource(id);
    if (source == null) {
      return;
    }

    final Throwable throwable = result.getThrowable().orElse(null);
    final String message = throwable == null ? "" : String.valueOf(throwable);
    final int line = failureLine(throwable, source.getClassName());
    write(record(source, statusOf(result.getStatus()), message, line));
  }

  @Override
  public void executionSkipped(final TestIdentifier id, final String reason) {
    final MethodSource source = methodSource(id);
    if (source == null) {
      return;
    }

    write(record(source, STATUS_SKIPPED, "", -1));
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
      final MethodSource source, final String status, final String message, final int line) {
    final String params = source.getMethodParameterTypes();
    return new TestRecord(
        source.getClassName(),
        source.getMethodName(),
        params == null ? "" : params,
        status,
        message,
        line);
  }

  private static MethodSource methodSource(final TestIdentifier id) {
    return id.getSource().orElse(null) instanceof final MethodSource source ? source : null;
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
    sb.append(",\"failureLine\":").append(record.failureLine()).append('}');
    return sb.toString();
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
