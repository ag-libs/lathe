package io.github.aglibs.lathe.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.TestCompiler;
import io.github.aglibs.lathe.server.workspace.WorkspaceManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MethodImplementationTest {

  @TempDir private Path tempDir;

  @Test
  void methodImplementations_genericOverride_returnsConcreteExactDeclaration() throws IOException {
    final String serviceContent = "interface Service<T> { T execute(T value); }\n";
    final var serviceSource = Files.writeString(tempDir.resolve("Service.java"), serviceContent);
    final var classDir = tempDir.resolve("classes");
    TestCompiler.compileToDir(classDir, serviceSource);
    final String candidateContent =
        """
        abstract class AbstractService implements Service<String> {
          public abstract String execute(String value);
        }
        class Direct extends AbstractService {
          public String execute(String value) { return value; }
          public String execute(Integer value) { return value.toString(); }
        }
        """;
    final var candidateUri = tempDir.resolve("Direct.java").toUri().toString();

    final ReferenceTarget target;
    try (var targetSession = new SourceAnalysisSession(new TempSourceCompiler())) {
      targetSession.compile(TempSourceCompiler.TEST_URI, serviceContent, 1, CompileMode.OPEN);
      target =
          targetSession.resolveTarget(
              new SourceFeatureRequest(
                  TempSourceCompiler.TEST_URI,
                  serviceContent,
                  0,
                  new Position(0, 27),
                  List.of(tempDir),
                  WorkspaceManifest.empty()));
    }

    try (var candidateSession =
        new SourceAnalysisSession(new TempSourceCompiler(List.of(classDir)))) {
      final List<Location> locations =
          implementations(
              candidateSession,
              candidateUri,
              candidateContent,
              target,
              Set.of("AbstractService", "Direct"));

      assertThat(locations).hasSize(1);
      assertThat(locations.get(0).getUri()).isEqualTo(candidateUri);
      assertThat(locations.get(0).getRange().getStart()).isEqualTo(new Position(4, 16));
      assertThat(locations.get(0).getRange().getEnd()).isEqualTo(new Position(4, 23));
    }
  }

  @Test
  void methodImplementations_recordComponentAccessor_returnsComponentDeclaration()
      throws IOException {
    final String serviceContent = "interface HasText { String text(); }\n";
    final var serviceSource = Files.writeString(tempDir.resolve("HasText.java"), serviceContent);
    final var classDir = tempDir.resolve("classes");
    TestCompiler.compileToDir(classDir, serviceSource);
    final String recordContent = "record Impl(String text) implements HasText {}\n";
    final var recordUri = tempDir.resolve("Impl.java").toUri().toString();

    final ReferenceTarget target;
    try (var targetSession = new SourceAnalysisSession(new TempSourceCompiler())) {
      targetSession.compile(TempSourceCompiler.TEST_URI, serviceContent, 1, CompileMode.OPEN);
      target =
          targetSession.resolveTarget(
              new SourceFeatureRequest(
                  TempSourceCompiler.TEST_URI,
                  serviceContent,
                  0,
                  new Position(0, 27),
                  List.of(tempDir),
                  WorkspaceManifest.empty()));
    }

    try (var candidateSession =
        new SourceAnalysisSession(new TempSourceCompiler(List.of(classDir)))) {
      final List<Location> locations =
          implementations(candidateSession, recordUri, recordContent, target, Set.of("Impl"));

      assertThat(locations).hasSize(1);
      assertThat(locations.get(0).getUri()).isEqualTo(recordUri);
      assertThat(locations.get(0).getRange().getStart()).isEqualTo(new Position(0, 19));
      assertThat(locations.get(0).getRange().getEnd()).isEqualTo(new Position(0, 23));
    }
  }

  @Test
  void implementation_outOfRangePosition_returnsEmpty() {
    final String content = "interface Service { void run(); }\n";
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      session.compile(TempSourceCompiler.TEST_URI, content, 1, CompileMode.OPEN);
      final var request =
          new SourceFeatureRequest(
              TempSourceCompiler.TEST_URI,
              content,
              0,
              new Position(9999, 0),
              List.of(tempDir),
              WorkspaceManifest.empty());
      assertThat(session.resolveTarget(request)).isNull();
    }
  }

  @Test
  void methodImplementations_nonMethodTarget_returnsEmpty() {
    final var target =
        new ReferenceTarget(
            javax.lang.model.element.ElementKind.CLASS,
            "Service",
            "Service",
            null,
            ReferenceTarget.SearchScope.REACTOR_MODULES,
            List.of(),
            false,
            -1);

    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      assertThat(
              implementations(
                  session,
                  TempSourceCompiler.TEST_URI,
                  "class Service {}",
                  target,
                  Set.of("Service")))
          .isEmpty();
    }
  }

  @Test
  void methodImplementationsTransient_candidateFile_doesNotCacheAnalysis() {
    final String uri = TempSourceCompiler.TEST_URI;
    final String content = "class Impl { void run() {} }";
    final var target =
        new ReferenceTarget(
            javax.lang.model.element.ElementKind.METHOD,
            "Service",
            "run",
            "()V",
            ReferenceTarget.SearchScope.REACTOR_MODULES,
            List.of(),
            false,
            -1);
    final var compiler = new CountingJavaSourceCompiler();

    try (var session = new SourceAnalysisSession(compiler)) {
      assertThat(
              session.queryFileTransient(
                  uri,
                  content,
                  analysis ->
                      MethodImplementationLocator.locate(analysis, target, Set.of("Impl"), uri)))
          .isEmpty();
      assertThat(implementations(session, uri, content, target, Set.of("Impl"))).isEmpty();

      assertThat(compiler.count(CompileMode.FAST)).isEqualTo(1);
      assertThat(compiler.count(CompileMode.OPEN)).isEqualTo(1);
    }
  }

  @Test
  void methodImplementations_functionalInterface_returnsSamImplsAndIgnoresOtherInterface()
      throws IOException {
    final String content = "interface Transformer { String apply(String input); }\n";
    final var source = Files.writeString(tempDir.resolve("Transformer.java"), content);
    final var classDir = tempDir.resolve("classes");
    TestCompiler.compileToDir(classDir, source);
    final String candidateContent =
        """
        import java.util.function.Supplier;
        class Impl implements Transformer {
          public String apply(String s) { return s; }
        }
        class Usage {
          Transformer a = s -> s.trim();
          Transformer b = String::toUpperCase;
          Supplier<String> other = () -> "ignored";
        }
        """;
    final var candidateUri = tempDir.resolve("Usage.java").toUri().toString();

    final ReferenceTarget target = samTarget(content);
    try (var candidateSession =
        new SourceAnalysisSession(new TempSourceCompiler(List.of(classDir)))) {
      final List<Location> locations =
          implementations(candidateSession, candidateUri, candidateContent, target, Set.of("Impl"));

      // The named override (apply, line 2), the Transformer lambda (line 5), and the Transformer
      // method reference (line 6) — but not the Supplier lambda (line 7), whose target type is a
      // different functional interface.
      assertThat(locations).allSatisfy(l -> assertThat(l.getUri()).isEqualTo(candidateUri));
      assertThat(locations.stream().map(l -> l.getRange().getStart().getLine()).toList())
          .containsExactlyInAnyOrder(2, 5, 6);
    }
  }

  @Test
  void methodImplementations_subInterfaces_matchInheritedSamButNotUnrelatedSam()
      throws IOException {
    final String content = "interface Transformer { String apply(String input); }\n";
    final var source = Files.writeString(tempDir.resolve("Transformer.java"), content);
    final var classDir = tempDir.resolve("classes");
    TestCompiler.compileToDir(classDir, source);
    // SubTransformer only inherits the SAM → its lambda (line 6) implements Transformer.apply.
    // Mixed defaults apply, so its SAM is Other.run → its lambda (line 7) implements run(), not
    // apply, and must be excluded.
    final String candidateContent =
        """
        interface SubTransformer extends Transformer {}
        interface Other { void run(); }
        interface Mixed extends Transformer, Other {
          default String apply(String s) { return s; }
        }
        class Usage {
          SubTransformer a = s -> s.trim();
          Mixed b = () -> {};
        }
        """;
    final var candidateUri = tempDir.resolve("Usage.java").toUri().toString();

    final ReferenceTarget target = samTarget(content);
    try (var candidateSession =
        new SourceAnalysisSession(new TempSourceCompiler(List.of(classDir)))) {
      final List<Location> locations =
          implementations(candidateSession, candidateUri, candidateContent, target, Set.of());

      assertThat(locations.stream().map(l -> l.getRange().getStart().getLine()).toList())
          .containsExactly(6);
    }
  }

  @Test
  void functionalInterfaceName_samVsNonSam_returnsNameOnlyForSam() {
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      final String sam = "interface Transformer { String apply(String input); }\n";
      session.compile(TempSourceCompiler.TEST_URI, sam, 1, CompileMode.OPEN);
      assertThat(session.functionalInterfaceName(samRequest(sam, new Position(0, 32))))
          .contains("Transformer");

      final String twoAbstract = "interface Two { void a(); void b(); }\n";
      session.compile(TempSourceCompiler.TEST_URI, twoAbstract, 2, CompileMode.OPEN);
      assertThat(session.functionalInterfaceName(samRequest(twoAbstract, new Position(0, 21))))
          .isEmpty();
    }
  }

  private static final String ANONYMOUS_SOURCE =
      """
      interface Managed { void stop(); }
      interface Other { void stop(); }
      enum Color {
        RED { String label() { return "r"; } },
        GREEN;
        String label() { return "g"; }
      }
      class Holder {
        Managed managed = new Managed() { public void stop() {} };
        Other other = new Other() { public void stop() {} };
        Managed lambda = () -> {};
        Managed reference = this::close;
        Runnable runnable = () -> {};
        void close() {}
      }
      """;

  // Anonymous classes, an enum constant's body among them, implement a method like named classes
  // do, found from the tree whatever javac numbered them, beside the lambdas of a functional
  // interface's method; an anonymous class of another interface with a same-named method does not,
  // nor a lambda of another functional interface (lines are 0-based).
  @Test
  void methodImplementations_anonymousClassAndEnumConstantBody_foundUnrelatedNot() {
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      final var uri = TempSourceCompiler.TEST_URI;

      assertThat(
              lines(
                  implementations(
                      session, uri, ANONYMOUS_SOURCE, targetAt("void stop()"), Set.of())))
          .containsExactlyInAnyOrder(8, 10, 11);
      assertThat(
              lines(
                  implementations(
                      session,
                      uri,
                      ANONYMOUS_SOURCE,
                      targetAt("String label() { return \"g"),
                      Set.of())))
          .containsExactly(3);
    }
  }

  // A type's implementations found from sources: its anonymous classes at their `new`, and for a
  // functional interface its lambdas and method references, not one converted to another interface.
  @Test
  void typeImplementations_functionalInterface_anonymousClassesLambdasAndMethodReferences() {
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      assertThat(
              lines(
                  implementations(
                      session,
                      TempSourceCompiler.TEST_URI,
                      ANONYMOUS_SOURCE,
                      targetAt("interface Managed"),
                      Set.of())))
          .containsExactlyInAnyOrder(8, 10, 11);
    }
  }

  private static List<Integer> lines(final List<Location> locations) {
    return locations.stream().map(location -> location.getRange().getStart().getLine()).toList();
  }

  // The target at the first `token` of ANONYMOUS_SOURCE, on its name.
  private ReferenceTarget targetAt(final String token) {
    final int at = ANONYMOUS_SOURCE.indexOf(token);
    final int name =
        at + (token.startsWith("interface ") ? "interface ".length() : token.indexOf(' ') + 1);
    try (var targetSession = new SourceAnalysisSession(new TempSourceCompiler())) {
      targetSession.compile(TempSourceCompiler.TEST_URI, ANONYMOUS_SOURCE, 1, CompileMode.OPEN);
      return targetSession.resolveTarget(
          samRequest(ANONYMOUS_SOURCE, SourceLocator.offsetToPosition(ANONYMOUS_SOURCE, name)));
    }
  }

  private static List<Location> implementations(
      final SourceAnalysisSession session,
      final String uri,
      final String content,
      final ReferenceTarget target,
      final Set<String> candidateBinaryNames) {
    return session.queryFile(
        uri,
        content,
        1,
        analysis ->
            MethodImplementationLocator.locate(analysis, target, candidateBinaryNames, uri));
  }

  private ReferenceTarget samTarget(final String interfaceContent) {
    try (var targetSession = new SourceAnalysisSession(new TempSourceCompiler())) {
      targetSession.compile(TempSourceCompiler.TEST_URI, interfaceContent, 1, CompileMode.OPEN);
      return targetSession.resolveTarget(samRequest(interfaceContent, new Position(0, 32)));
    }
  }

  private SourceFeatureRequest samRequest(final String content, final Position pos) {
    return new SourceFeatureRequest(
        TempSourceCompiler.TEST_URI, content, 0, pos, List.of(tempDir), WorkspaceManifest.empty());
  }
}
