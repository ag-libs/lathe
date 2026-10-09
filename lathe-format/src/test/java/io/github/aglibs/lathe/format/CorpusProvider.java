package io.github.aglibs.lathe.format;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;

// The inputs every conformance check runs over: the vendored GJF golden pairs, targeted snippets,
// and an opt-in external corpus. Inputs needing a newer JDK are gated, like GJF itself does.
final class CorpusProvider {

  private CorpusProvider() {}

  // Opt-in: -Dlathe.format.corpus=<dir> runs the corpus tests over every .java file below <dir>.
  static final String CORPUS_PROPERTY = "lathe.format.corpus";

  private static final int BASELINE = 21;

  private static final Map<String, Integer> MIN_FEATURE =
      Map.ofEntries(
          Map.entry("SwitchGuardClause", 21),
          Map.entry("SwitchRecord", 21),
          Map.entry("SwitchDouble", 21),
          Map.entry("SwitchUnderscore", 21),
          Map.entry("I880", 21),
          Map.entry("Unnamed", 21),
          Map.entry("I981", 21),
          Map.entry("I1020", 21),
          Map.entry("I1037", 21),
          Map.entry("ModuleImport", 25),
          Map.entry("InstanceMain", 25));

  // Each targets a place where a javac internal was replaced: positional predicates, operator
  // tables, annotation ordering, javadoc references, the public parse options. All must format.
  private record Snippet(String name, int minFeature, String source) {

    private Snippet {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(source, "source");
    }
  }

  private static final List<Snippet> SNIPPETS =
      List.of(
          new Snippet(
              "operators",
              BASELINE,
              """
              class Ops {
                void m(int a, int b, boolean c) {
                  a += b; a -= b; a *= b; a /= b; a %= b; a &= b; a |= b; a ^= b;
                  a <<= b; a >>= b; a >>>= b; a = b;
                  int x = a + b - a * b / a % b << 1 >> 2 >>> 3 & a | b ^ a;
                  boolean y = a < b && a > b || a <= b & a >= b | a == b ^ a != b;
                  boolean z = !c ? ~a > 0 : -a < +b;
                  boolean w = c instanceof Boolean bb && bb;
                  int longest = alphaValue * betaValue + gammaValue * deltaValue - epsilonValue / zetaValue + etaValue % thetaValue << iotaValue | kappaValue & lambdaValue ^ muValue;
                  boolean chain = firstCondition && secondCondition || thirdCondition && !fourthCondition || fifthCondition == sixth;
                }
              }
              """),
          new Snippet(
              "unary",
              BASELINE,
              """
              class Unary {
                int m(int i, int j) {
                  i++; ++i; i--; --i;
                  int a = - -i; int b = -(-i); int c = +-i; int d = i++ + ++j; int e = i-- - --j;
                  int f = -1; long g = -2147483648L; double h = - 1.5;
                  return - - -i;
                }
              }
              """),
          new Snippet(
              "records",
              BASELINE,
              """
              record Z() { Z {} }
              record R(int x, String... ys) { /** Doc. */ @Deprecated public R { } static int count; }
              record S(int x) { S(int x) { this.x = x; } }
              record A(@Deprecated int x, @SuppressWarnings("x") java.util.List<String> y) implements Runnable { public void run() {} }
              class O { void m() { record L<T>(T t) implements Comparable<L<T>> { public int compareTo(L<T> o) { return 0; } } } }
              """),
          new Snippet(
              "enums",
              BASELINE,
              """
              enum E {
                /** Doc. */ @Deprecated A, B(1) { @Override public String toString() { return "b"; } }, C;
                E X = null;
                static final E Y = A;
                private final int v;
                E() { this(0); }
                E(int v) { this.v = v; }
              }
              """),
          new Snippet(
              "modifier-order",
              BASELINE,
              """
              class Mods {
                @Deprecated public @SuppressWarnings("x") static final @Nullable String s = null;
                final @Deprecated private static int x = 1;
                @Override public synchronized @Deprecated String toString() { return ""; }
                public @interface Nullable {}
              }
              """),
          new Snippet(
              "unnamed",
              BASELINE,
              """
              class Unnamed {
                void m(Object o, java.util.List<String> l) {
                  l.forEach(_ -> {});
                  try { run(); } catch (Exception _) { }
                  for (var _ : l) {}
                  switch (o) { case String _ -> {} case Point(var x, _) -> {} default -> {} }
                }
                record Point(int x, int y) {}
              }
              """),
          new Snippet(
              "string-concat",
              BASELINE,
              """
              class Concat {
                String s = String.format("value %s and %d and more text to push this past the column limit " + "second %s" + " third", a, b, c);
                String t = "plain text that is long enough to need wrapping when joined " + "with another literal piece";
              }
              """),
          new Snippet(
              "javadoc-member-and-params",
              BASELINE,
              """
              import a.List;
              import b.Map;
              import c.Entry;
              import d.Unused;
              /** {@link List#add(Map.Entry, int[])} */ class A {}
              """),
          new Snippet(
              "javadoc-module-and-label",
              BASELINE,
              """
              import java.util.List;
              import java.util.Map;
              /** {@link java.base/java.util.List} and {@linkplain Map the map} */ class A {}
              """),
          new Snippet(
              "javadoc-throws-and-see",
              BASELINE,
              """
              import java.io.IOException;
              import x.Foo;
              class A {
                /**
                 * @throws IOException never
                 * @see Foo.Bar#baz(String...)
                 */
                void m() {}
              }
              """),
          new Snippet(
              "javadoc-generic-params",
              BASELINE,
              """
              import a.K;
              import b.V;
              import c.Gone;
              /** {@link #put(java.util.Map<K, V>, ? extends Gone.Inner)} */ class A {}
              """),
          new Snippet(
              "javadoc-code-is-not-reference",
              BASELINE,
              """
              import a.Used;
              import b.Gone;
              class A { Used u; /** not a {@code Gone} reference */ int x; }
              """),
          new Snippet(
              "flexible-constructor",
              25,
              """
              class Flexible extends Object {
                Flexible(int x) {
                  if (x < 0) throw new IllegalArgumentException();
                  super();
                }
              }
              """),
          new Snippet(
              "module-import", 25, "import module java.base;\nclass M { List<String> l; }\n"),
          new Snippet(
              "implicit-class",
              25,
              "int counter;\nvoid main() { counter++; }\nString name() { return \"x\"; }\n"),
          new Snippet(
              "primitive-patterns",
              25,
              """
              class Primitive {
                void m(Object o, int v) {
                  switch (o) { case Integer i when i > 0 -> {} default -> {} }
                  if (v instanceof byte b) {}
                }
              }
              """));

  static Stream<Arguments> goldenCases() throws Exception {
    final Path root = Path.of(CorpusProvider.class.getResource("/golden").toURI());
    final List<Arguments> cases = new ArrayList<>();
    try (Stream<Path> files = Files.list(root)) {
      for (final Path input : files.filter(CorpusProvider::isInput).sorted().toList()) {
        final String name = baseName(input);
        if (!isSupported(MIN_FEATURE.getOrDefault(name, BASELINE))) {
          continue;
        }

        final String source = Files.readString(input);
        final String expected = Files.readString(root.resolve(name + ".output"));
        cases.add(Arguments.of(name, source, expected));
      }
    }

    return cases.stream();
  }

  static Stream<Arguments> snippets() {
    return SNIPPETS.stream()
        .filter(snippet -> isSupported(snippet.minFeature()))
        .map(snippet -> Arguments.of(snippet.name(), snippet.source()));
  }

  static Stream<Path> corpusFiles() throws Exception {
    final Path root = Path.of(System.getProperty(CORPUS_PROPERTY));
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(path -> path.toString().endsWith(".java")).sorted().toList().stream();
    }
  }

  private static boolean isSupported(final int minFeature) {
    return Runtime.version().feature() >= minFeature;
  }

  private static boolean isInput(final Path path) {
    return path.getFileName().toString().endsWith(".input");
  }

  private static String baseName(final Path input) {
    final String fileName = input.getFileName().toString();
    return fileName.substring(0, fileName.length() - ".input".length());
  }
}
