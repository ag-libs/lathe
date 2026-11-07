package io.github.aglibs.lathe.server;

import static io.github.aglibs.lathe.analysis.Positions.offsetToPosition;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.engine.LatheCall;
import io.github.aglibs.lathe.server.engine.LatheCallHierarchy;
import io.github.aglibs.lathe.server.engine.LatheFileEdit;
import io.github.aglibs.lathe.server.engine.LatheLocation;
import io.github.aglibs.lathe.server.engine.LatheReferences;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

// A static method's search compiles first the files naming its family, and the files that only
// spell its name when one of those exposes the family to files that never name it.
class StaticMethodSearchTest extends LatheEngineTestBase {

  private static final String UTIL =
      """
      package com.example;

      public class Util {
        public static boolean isEmpty(String s) {
          return s.length() == 0;
        }
      }
      """;

  private static final String UNRELATED =
      """
      package com.example;

      import java.util.List;

      class Unrelated {
        boolean check(List<String> list) {
          return list.isEmpty();
        }
      }
      """;

  @Test
  void search_staticMethodOfAnUnexposedType_findsEveryCallWithoutFilesSpellingOnlyItsName()
      throws Exception {
    final var sources = new LinkedHashMap<String, String>();
    sources.put("com/example/Util.java", UTIL);
    sources.put(
        "com/example/Caller.java",
        "package com.example;\nclass Caller { boolean check(String s) { return Util.isEmpty(s); } }\n");
    sources.put(
        "com/example/SubUtil.java",
        "package com.example;\nclass SubUtil extends Util { boolean check(String s) { return isEmpty(s); } }\n");
    sources.put("com/example/Unrelated.java", UNRELATED);
    final Map<String, Path> files = writeModule(sources);
    engine = new LatheEngine(tmp);

    try (var compileLog = new ServiceTestBase.CompileLog(tmp)) {
      final LatheReferences references = referencesToIsEmpty(files);
      final LatheCallHierarchy callers = callersOfIsEmpty(files);

      assertThat(references.references())
          .extracting(LatheLocation::uri)
          .anySatisfy(uri -> assertThat(uri).endsWith("Caller.java"))
          .anySatisfy(uri -> assertThat(uri).endsWith("SubUtil.java"))
          .noneSatisfy(uri -> assertThat(uri).endsWith("Unrelated.java"));
      assertThat(callers.calls())
          .extracting(call -> call.location().uri())
          .anySatisfy(uri -> assertThat(uri).endsWith("Caller.java"))
          .anySatisfy(uri -> assertThat(uri).endsWith("SubUtil.java"));
      assertThat(compileLog.messages())
          .as("the file spelling only the method's name is never compiled")
          .noneMatch(message -> message.contains("Unrelated.java"));
    }
  }

  // Holder.util() returns a Util, so Hidden calls Util.isEmpty through it without naming Util:
  // Holder exposes the family, and the files spelling only the method's name are searched too.
  @Test
  void search_staticMethodReachedThroughAnExposingDeclaration_findsTheCallInAFileNotNamingIt()
      throws Exception {
    final var sources = new LinkedHashMap<String, String>();
    sources.put("com/example/Util.java", UTIL);
    sources.put(
        "com/example/Holder.java",
        "package com.example;\npublic class Holder { public static Util util() { return new Util(); } }\n");
    sources.put(
        "com/example/Hidden.java",
        "package com.example;\nclass Hidden { boolean check(String s) { return Holder.util().isEmpty(s); } }\n");
    sources.put("com/example/Unrelated.java", UNRELATED);
    final Map<String, Path> files = writeModule(sources);
    engine = new LatheEngine(tmp);

    final LatheReferences references = referencesToIsEmpty(files);
    final LatheCallHierarchy callers = callersOfIsEmpty(files);

    assertThat(references.references())
        .extracting(LatheLocation::uri)
        .anySatisfy(uri -> assertThat(uri).endsWith("Hidden.java"));
    assertThat(callers.calls())
        .extracting(LatheCall::location)
        .extracting(LatheLocation::uri)
        .anySatisfy(uri -> assertThat(uri).endsWith("Hidden.java"));
    assertThat(renameAt(files.get("Util"), UTIL, "isEmpty", "isBlankText").files())
        .extracting(LatheFileEdit::uri)
        .anySatisfy(uri -> assertThat(uri).endsWith("Hidden.java"));
    assertThat(Files.readString(files.get("Hidden"))).contains("Holder.util().isBlankText(s)");
  }

  // A package-private static method's callers share its package, but the declaration exposing its
  // type to them (another package's public Api.get()) is found module-wide.
  @Test
  void search_packagePrivateStaticMethodExposedFromAnotherPackage_findsTheCallInItsPackage()
      throws Exception {
    final String check =
        """
        package com.example.a;

        public class Check {
          static boolean blank(String s) {
            return s.length() == 0;
          }
        }
        """;
    final var sources = new LinkedHashMap<String, String>();
    sources.put("com/example/a/Check.java", check);
    sources.put(
        "com/example/b/Api.java",
        "package com.example.b;\npublic class Api { public static com.example.a.Check get() { return null; } }\n");
    sources.put(
        "com/example/a/Caller.java",
        "package com.example.a;\nclass Caller { boolean f() { return com.example.b.Api.get().blank(\"\"); } }\n");
    sources.put(
        "com/example/a/Other.java",
        "package com.example.a;\nclass Other { boolean blank() { return true; } boolean f() { return blank(); } }\n");
    sources.put(
        "com/example/a/Another.java",
        "package com.example.a;\nclass Another { boolean blank() { return false; } }\n");
    final Map<String, Path> files = writeModule(sources);
    engine = new LatheEngine(tmp);

    final var pos = offsetToPosition(check, check.indexOf("blank"));
    final LatheReferences references =
        engine.references(files.get("Check"), pos.getLine(), pos.getCharacter(), 50);

    assertThat(references.references())
        .extracting(LatheLocation::uri)
        .anySatisfy(uri -> assertThat(uri).endsWith("a/Caller.java"));
  }

  private Map<String, Path> writeModule(final Map<String, String> sources) throws Exception {
    final List<Path> paths =
        TestCompiler.writeMirroredModule(tmp, "module", sources, List.of(), 1_000L);
    final var byName = new LinkedHashMap<String, Path>();
    for (final Path path : paths) {
      byName.put(path.getFileName().toString().replace(".java", ""), path);
    }

    return byName;
  }

  private LatheReferences referencesToIsEmpty(final Map<String, Path> files) {
    final var pos = offsetToPosition(UTIL, UTIL.indexOf("isEmpty"));
    return engine.references(files.get("Util"), pos.getLine(), pos.getCharacter(), 50);
  }

  private LatheCallHierarchy callersOfIsEmpty(final Map<String, Path> files) {
    final var pos = offsetToPosition(UTIL, UTIL.indexOf("isEmpty"));
    return engine.callHierarchy(
        files.get("Util"),
        pos.getLine(),
        pos.getCharacter(),
        LatheEngine.CallDirection.INCOMING,
        LatheEngine.SymbolScope.ALL,
        50);
  }
}
