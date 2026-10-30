package io.github.aglibs.lathe.server;

// Sources that javac itself fails on while Lathe attributes past their errors, which command-line
// javac never does.
public final class JavacCrashFixtures {

  // A method whose `try {` line was deleted: javac's Attr meets the stray catch with an
  // AssertionError.
  public static final String TRY_LINE_DELETED =
      """
      class Foo {
        int run(boolean flag) {
          if (flag) {
              return 1;
            }
            catch (RuntimeException e) {
              return 2;
            }
          }
          return 0;
        }
      }
      """;

  private JavacCrashFixtures() {}
}
