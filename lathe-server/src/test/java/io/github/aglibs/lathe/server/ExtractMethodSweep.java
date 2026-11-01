package io.github.aglibs.lathe.server;

import com.google.gson.JsonObject;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import io.github.aglibs.lathe.core.FileUtil;
import io.github.aglibs.lathe.core.IOUtil;
import io.github.aglibs.lathe.core.Stopwatch;
import io.github.aglibs.lathe.server.analysis.ExtractionSupport;
import io.github.aglibs.validcheck.ValidCheck;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;

// Dev tool, not a test: runs extract method over every candidate selection of a real repo through
// the editor's own path (open, codeAction, codeAction/resolve) and reports crashes, false offers
// (offered, then refused by the compile check), refusals by reason, offers by shape and timing.
// Candidates: every run of 1-5 consecutive statements of a block or `case:`, and every
// initializer, argument and return value. It only reads the repo.
//
//   java -Xmx3g -cp <server jar>:<test classes>:<deps>
// io.github.aglibs.lathe.server.ExtractMethodSweep
//       <repo> <out dir> [--files N] [--seed S] [--resolve FRACTION]
public final class ExtractMethodSweep {

  private static final int MAX_RUN = 5;
  private static final long TIMEOUT_SECONDS = 120;
  // Held strongly: JUL keeps loggers weakly, and a collected one would take its level with it.
  private static final Logger PROVIDER_LOG =
      Logger.getLogger("io.github.aglibs.lathe.server.analysis.ExtractMethodProvider");
  private static final Pattern REFUSED =
      Pattern.compile("^\\[codeAction:extractMethod\\] refused: \\[(.*)\\]$");

  private final Path repo;
  private final Path out;
  private final double resolveFraction;
  private final Random random;
  private final LatheTextDocumentService service = new LatheTextDocumentService();
  private final ConcurrentLinkedQueue<LogRecord> records = new ConcurrentLinkedQueue<>();
  private final Map<String, Integer> outcomes = new TreeMap<>();
  private final Map<String, Integer> refusals = new TreeMap<>();
  private final Map<String, Integer> shapes = new TreeMap<>();
  private final List<Long> requestMs = new ArrayList<>();
  private final List<Long> resolveMs = new ArrayList<>();
  private int findings;
  private boolean fileHasErrors;

  private record Candidate(String kind, long start, long end) {

    Candidate {
      ValidCheck.check().notNull(kind, "kind").assertTrue(start < end, "span").validate();
    }
  }

  private ExtractMethodSweep(
      final Path repo, final Path out, final double resolveFraction, final long seed) {
    this.repo = repo;
    this.out = out;
    this.resolveFraction = resolveFraction;
    this.random = new Random(seed);
  }

  public static void main(final String[] args) throws Exception {
    final Path repo = Path.of(args[0]).toAbsolutePath().normalize();
    final Path out = Path.of(args[1]).toAbsolutePath().normalize();
    final int files = intOption(args, "--files", Integer.MAX_VALUE);
    final long seed = intOption(args, "--seed", 1);
    final double resolve = Double.parseDouble(option(args, "--resolve", "1"));
    Files.createDirectories(out);
    Files.deleteIfExists(out.resolve("findings.md"));
    Files.deleteIfExists(out.resolve("results.jsonl"));
    final var sweep = new ExtractMethodSweep(repo, out, resolve, seed);
    try {
      sweep.run(files);
    } finally {
      sweep.service.close();
    }

    System.exit(0);
  }

  private void run(final int maxFiles) throws Exception {
    captureLogs();
    service.connect(noopClient());
    service.setDocumentsFollowDisk(true);
    service.setCodeActionResolveSupported(true);
    service.initialize(repo);

    final List<Path> files =
        new ArrayList<>(FileUtil.walkFiles(repo, ExtractMethodSweep::isSource));
    Collections.sort(files);
    Collections.shuffle(files, random);
    final List<Path> chosen = files.subList(0, Math.min(maxFiles, files.size()));
    final var t = Stopwatch.start();
    for (int i = 0; i < chosen.size(); i++) {
      final Path file = chosen.get(i);
      final int done = i;
      sweepFile(file);
      if (done % 20 == 0) {
        System.err.printf(
            "[sweep] %d/%d files %ds %s%n", done, chosen.size(), t.elapsedMs() / 1000, outcomes);
      }
    }

    report(chosen.size(), t.elapsedMs());
  }

  // Sources a module compiles: main and test trees, never build output or Lathe's own mirror.
  private static boolean isSource(final Path path) {
    final String text = FileUtil.slashed(path);
    return FileUtil.isJavaFile(path)
        && (text.contains("/src/main/java/") || text.contains("/src/test/java/"))
        && !text.contains("/target/")
        && !text.contains("/.lathe/");
  }

  private void sweepFile(final Path file) throws Exception {
    final String content = Files.readString(file, StandardCharsets.UTF_8);
    final List<Candidate> candidates = candidates(file, content);
    if (candidates.isEmpty()) {
      return;
    }

    final String uri = LatheUri.fromPath(file);
    // A file that does not compile as it is makes every compile check fail: counted apart.
    fileHasErrors =
        service.diagnosticsFuture(uri, content, 1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).stream()
            .anyMatch(d -> d.getSeverity() == DiagnosticSeverity.Error);
    if (fileHasErrors) {
      count(outcomes, "files with errors on open");
    }

    records.clear();
    final int[] lineStarts = lineStarts(content);
    for (final Candidate candidate : candidates) {
      sweepCandidate(file, uri, content, lineStarts, candidate);
    }

    service.didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri)));
  }

  private void sweepCandidate(
      final Path file,
      final String uri,
      final String content,
      final int[] lineStarts,
      final Candidate candidate)
      throws Exception {
    final var range =
        new Range(position(lineStarts, candidate.start()), position(lineStarts, candidate.end()));
    final var params =
        new CodeActionParams(
            new TextDocumentIdentifier(uri), range, new CodeActionContext(List.of()));
    final var t = Stopwatch.start();
    final List<Either<Command, CodeAction>> actions;
    try {
      actions = service.codeAction(params).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (final Exception e) {
      count(outcomes, "crash");
      finding("crash", file, candidate, content, e.toString());
      return;
    }

    requestMs.add(t.elapsedMs());
    final List<LogRecord> logged = drainRecords();
    logged.stream()
        .filter(r -> r.getLevel().intValue() >= Level.SEVERE.intValue())
        .forEach(r -> finding("crash", file, candidate, content, describe(r)));
    final List<CodeAction> offered =
        actions.stream()
            .filter(Either::isRight)
            .map(Either::getRight)
            .filter(a -> ExtractionSupport.METHOD_KIND.equals(a.getKind()))
            .toList();
    if (offered.isEmpty()) {
      final List<String> reasons =
          logged.stream()
              .map(LogRecord::getMessage)
              .map(REFUSED::matcher)
              .filter(java.util.regex.Matcher::matches)
              .flatMap(m -> List.of(m.group(1).split(", ")).stream())
              .distinct()
              .toList();
      reasons.forEach(reason -> count(refusals, reason));
      final String outcome = reasons.isEmpty() ? "not a region" : "refused";
      count(outcomes, "%s %s".formatted(candidate.kind(), outcome));
      line(file, candidate, outcome, String.join("; ", reasons));
      return;
    }

    count(outcomes, "%s offered".formatted(candidate.kind()));
    for (final CodeAction action : offered) {
      final String shape = shape(action);
      count(shapes, shape);
      if (random.nextDouble() < resolveFraction) {
        resolve(file, candidate, content, action, shape);
      }
    }
  }

  private void resolve(
      final Path file,
      final Candidate candidate,
      final String content,
      final CodeAction action,
      final String shape)
      throws Exception {
    final JsonObject data = (JsonObject) action.getData();
    final var t = Stopwatch.start();
    final CodeAction resolved =
        service.resolveCodeAction(action).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    resolveMs.add(t.elapsedMs());
    if (resolved.getDisabled() == null) {
      count(outcomes, "resolved ok");
      line(file, candidate, "ok", shape);
      return;
    }

    final String kind = fileHasErrors ? "false offer, file had errors" : "FALSE OFFER";
    count(outcomes, "resolved refused: %s".formatted(kind));
    finding(
        "%s: %s".formatted(kind, resolved.getDisabled().getReason()),
        file,
        candidate,
        content,
        "%s\n%s".formatted(shape, data.get("edits")));
    line(file, candidate, kind, resolved.getDisabled().getReason());
  }

  // What the offer would write: replace-all or single, and the call's form.
  private static String shape(final CodeAction action) {
    final String edits = ((JsonObject) action.getData()).get("edits").toString();
    final String call;
    if (edits.contains("return extracted")) {
      call = "returns the call";
    } else if (edits.contains(" = extracted")) {
      call = "value out";
    } else if (edits.contains("private void ") || edits.contains("private static void ")) {
      call = "void";
    } else {
      call = "expression";
    }

    final String generic = edits.matches(".*private (static )?<.*") ? " generic" : "";
    final String all = action.getTitle().contains("replace all") ? "replace-all " : "";
    return "%s%s%s".formatted(all, call, generic);
  }

  // ── candidates ─────────────────────────────────────────────────────────────────────────────

  private static List<Candidate> candidates(final Path file, final String content)
      throws Exception {
    final JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    final var source =
        new SimpleJavaFileObject(
            URI.create(file.toUri().toString()), javax.tools.JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
            return content;
          }
        };
    final var task =
        (JavacTask)
            javac.getTask(null, null, d -> {}, List.of("-proc:none"), null, List.of(source));
    final List<Candidate> found = new ArrayList<>();
    for (final CompilationUnitTree cu : task.parse()) {
      final SourcePositions positions = Trees.instance(task).getSourcePositions();
      new TreeScanner<Void, Void>() {
        @Override
        public Void visitBlock(final BlockTree tree, final Void unused) {
          runs(tree.getStatements());
          return super.visitBlock(tree, unused);
        }

        @Override
        public Void visitCase(final CaseTree tree, final Void unused) {
          if (tree.getStatements() != null) {
            runs(tree.getStatements());
          }

          return super.visitCase(tree, unused);
        }

        @Override
        public Void visitVariable(final VariableTree tree, final Void unused) {
          value("initializer", tree.getInitializer());
          return super.visitVariable(tree, unused);
        }

        @Override
        public Void visitMethodInvocation(final MethodInvocationTree tree, final Void unused) {
          tree.getArguments().forEach(argument -> value("argument", argument));
          return super.visitMethodInvocation(tree, unused);
        }

        @Override
        public Void visitReturn(final ReturnTree tree, final Void unused) {
          value("return value", tree.getExpression());
          return super.visitReturn(tree, unused);
        }

        private void runs(final List<? extends StatementTree> statements) {
          IntStream.range(0, statements.size())
              .forEach(
                  first ->
                      IntStream.rangeClosed(1, Math.min(MAX_RUN, statements.size() - first))
                          .forEach(
                              length ->
                                  add(
                                      "statements",
                                      statements.get(first),
                                      statements.get(first + length - 1))));
        }

        private void value(final String kind, final ExpressionTree expression) {
          if (expression != null) {
            add(kind, expression, expression);
          }
        }

        private void add(final String kind, final Tree first, final Tree last) {
          final long start = positions.getStartPosition(cu, first);
          final long end = positions.getEndPosition(cu, last);
          if (start >= 0 && end > start) {
            found.add(new Candidate(kind, start, end));
          }
        }
      }.scan(cu, null);
    }

    return found;
  }

  // ── plumbing ───────────────────────────────────────────────────────────────────────────────

  private static int[] lineStarts(final String content) {
    return IntStream.concat(
            IntStream.of(0),
            IntStream.range(0, content.length())
                .filter(i -> content.charAt(i) == '\n')
                .map(i -> i + 1))
        .toArray();
  }

  private static Position position(final int[] lineStarts, final long offset) {
    int line = java.util.Arrays.binarySearch(lineStarts, (int) offset);
    if (line < 0) {
      line = -line - 2;
    }

    return new Position(line, (int) offset - lineStarts[line]);
  }

  // Refusal reasons are the provider's FINE line; crashes are any SEVERE record. Nothing else is
  // printed, so the console shows only the progress lines.
  private void captureLogs() {
    LogManager.getLogManager().reset();
    final var handler =
        new Handler() {
          @Override
          public void publish(final LogRecord record) {
            records.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    final Logger root = Logger.getLogger("");
    root.setLevel(Level.WARNING);
    root.addHandler(handler);
    PROVIDER_LOG.setLevel(Level.FINE);
  }

  private List<LogRecord> drainRecords() {
    final List<LogRecord> drained = new ArrayList<>();
    for (LogRecord r = records.poll(); r != null; r = records.poll()) {
      drained.add(r);
    }

    return drained;
  }

  private static String describe(final LogRecord record) {
    final Throwable thrown = record.getThrown();
    if (thrown == null) {
      return record.getMessage();
    }

    final var stack = new java.io.StringWriter();
    thrown.printStackTrace(new java.io.PrintWriter(stack));
    return "%s\n%s".formatted(record.getMessage(), stack);
  }

  // A client that accepts every notification and answers every request with nothing.
  private static LanguageClient noopClient() {
    return (LanguageClient)
        Proxy.newProxyInstance(
            LanguageClient.class.getClassLoader(),
            new Class<?>[] {LatheLanguageClient.class},
            (proxy, method, args) ->
                method.getReturnType() == CompletableFuture.class
                    ? CompletableFuture.completedFuture(null)
                    : null);
  }

  private static void count(final Map<String, Integer> counts, final String key) {
    counts.merge(key, 1, Integer::sum);
  }

  private void finding(
      final String what,
      final Path file,
      final Candidate candidate,
      final String content,
      final String detail) {
    final String selected = content.substring((int) candidate.start(), (int) candidate.end());
    findings++;
    append(
        "findings.md",
        "### %s\n\n`%s` offsets %d-%d (%s)\n\n```java\n%s\n```\n\n```\n%s\n```\n"
            .formatted(
                what,
                repo.relativize(file),
                candidate.start(),
                candidate.end(),
                candidate.kind(),
                selected,
                detail));
  }

  private void line(
      final Path file, final Candidate candidate, final String outcome, final String detail) {
    final var json = new JsonObject();
    json.addProperty("file", FileUtil.slashed(repo.relativize(file)));
    json.addProperty("start", candidate.start());
    json.addProperty("end", candidate.end());
    json.addProperty("kind", candidate.kind());
    json.addProperty("outcome", outcome);
    json.addProperty("detail", detail);
    append("results.jsonl", json.toString());
  }

  private void report(final int files, final long elapsedMs) {
    final String summary =
        """
        # Extract method sweep: %s

        Files: %d, elapsed: %ds, resolve fraction: %s

        ## Outcomes
        %s
        ## Refusal reasons
        %s
        ## Offer shapes
        %s
        ## Timing (ms)
        codeAction: %s
        resolve:    %s

        Findings (crashes, false offers): %d, in findings.md
        """
            .formatted(
                repo,
                files,
                elapsedMs / 1000,
                resolveFraction,
                table(outcomes),
                table(refusals),
                table(shapes),
                percentiles(requestMs),
                percentiles(resolveMs),
                findings);
    System.out.println(summary);
    IOUtil.unchecked(() -> Files.writeString(out.resolve("summary.md"), summary));
  }

  // Written as found, so a long run can be read while it goes.
  private void append(final String name, final String text) {
    IOUtil.unchecked(
        () ->
            Files.writeString(
                out.resolve(name),
                text + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND));
  }

  private static String table(final Map<String, Integer> counts) {
    return counts.entrySet().stream()
        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
        .map(e -> "- %s: %d\n".formatted(e.getKey(), e.getValue()))
        .collect(Collectors.joining());
  }

  private static String percentiles(final List<Long> ms) {
    if (ms.isEmpty()) {
      return "none";
    }

    final List<Long> sorted = ms.stream().sorted().toList();
    return "n=%d p50=%d p90=%d p99=%d max=%d"
        .formatted(
            sorted.size(),
            sorted.get(sorted.size() / 2),
            sorted.get(sorted.size() * 9 / 10),
            sorted.get(Math.min(sorted.size() - 1, sorted.size() * 99 / 100)),
            sorted.get(sorted.size() - 1));
  }

  private static String option(final String[] args, final String name, final String fallback) {
    for (int i = 2; i + 1 < args.length; i++) {
      if (args[i].equals(name)) {
        return args[i + 1];
      }
    }

    return fallback;
  }

  private static int intOption(final String[] args, final String name, final int fallback) {
    return Integer.parseInt(option(args, name, Integer.toString(fallback)));
  }
}
