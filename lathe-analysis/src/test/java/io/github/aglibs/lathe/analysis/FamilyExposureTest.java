package io.github.aglibs.lathe.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.analysis.testing.TempSourceCompiler;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FamilyExposureTest {

  private static final String UTIL =
      """
      class Util {
        static Util INSTANCE = new Util();
        static Util create() { return new Util(); }
        static boolean isEmpty(String s) { return s.isEmpty(); }
      }
      """;

  private static List<String> exposures(final String source, final Set<String> family) {
    try (var compiler = new TempSourceCompiler()) {
      final AttributedFileAnalysis analysis =
          compiler.compile(TempSourceCompiler.TEST_URI, source, CompileMode.OPEN).fileAnalysis();
      return FamilyExposure.exposures(analysis, family);
    }
  }

  @Test
  void exposures_nonPrivateDeclarationsMentioningTheFamily_listsEach() {
    final String source =
        """
        import java.util.List;
        import java.util.function.Consumer;
        import java.util.function.Supplier;

        class Holder {
          Util util() { return null; }
        }

        class Registry {
          void register(Consumer<Util> callback) {}
        }

        class Fields {
          protected Util shared;
        }

        record Box(Util util) {}

        abstract class Supply implements Supplier<Util> {}

        class Bounded<X extends Util> {}

        interface Handler {
          void handle(Util util);
        }

        class Factory {
          <X extends Util> X make() { return null; }
          List<? extends Util> all() { return List.of(); }
          Util[] many() { return new Util[0]; }
        }
        """
            + UTIL;

    assertThat(exposures(source, Set.of("Util")))
        .containsExactlyInAnyOrder(
            "Holder.util",
            "Registry.register",
            "Fields.shared",
            "Box.util",
            "Supply",
            "Bounded",
            "Factory.make",
            "Factory.all",
            "Factory.many",
            "Handler.handle");
  }

  // A private member or a private type is seen only inside its file, a family type's own members
  // are reached only by naming it, and a class's parameter takes the caller's family value rather
  // than handing one out: none lets a file that never names the family call it.
  @Test
  void exposures_privateFamilyOwnOrParameterOnlyDeclarations_listsNone() {
    final String source =
        """
        class Service {
          Service(Util util) {}

          void take(Util util, Util[] more) {}
        }

        class Caller {
          private Util cached;

          private Util make() { return Util.create(); }

          boolean check(String s) { return Util.isEmpty(s) && make() != null; }

          private static class Hidden {
            public Util util() { return null; }
          }
        }
        """
            + UTIL;

    assertThat(exposures(source, Set.of("Util"))).isEmpty();
  }

  @Test
  void exposures_subtypesTheIndexLacks_listsThemUntilTheyAreFamily() {
    final String source =
        """
        class Sub extends Util {}

        class SubSub extends Sub {}
        """
            + UTIL;

    assertThat(exposures(source, Set.of("Util")))
        .containsExactlyInAnyOrder(
            "Sub (a subtype not yet indexed)", "SubSub (a subtype not yet indexed)");
    assertThat(exposures(source, Set.of("Util", "Sub", "SubSub"))).isEmpty();
  }

  @Test
  void exposures_noFamily_listsNone() {
    final String source =
        """
        class Holder {
          Util util() { return null; }
        }
        """
            + UTIL;

    assertThat(exposures(source, Set.of())).isEmpty();
  }
}
