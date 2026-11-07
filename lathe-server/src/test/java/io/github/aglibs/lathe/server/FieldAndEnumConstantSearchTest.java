package io.github.aglibs.lathe.server;

import static io.github.aglibs.lathe.analysis.Positions.offsetToPosition;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.engine.LatheFileEdit;
import io.github.aglibs.lathe.server.engine.LatheLocation;
import io.github.aglibs.lathe.server.engine.LatheReferences;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

// A field read through an expression whose type the reading file never names, and an enum constant
// labelling a switch over one, are found and renamed; a same-named member of another type is not.
class FieldAndEnumConstantSearchTest extends LatheEngineTestBase {

  private static final String OWNER =
      """
      package com.example;

      public class Owner {
        public String name;
      }
      """;

  private static final String KIND =
      """
      package com.example;

      public enum Kind { ALPHA, BETA }
      """;

  @Test
  void referencesAndRename_fieldReadThroughUnnamedType_reachTheReadButNotOtherFields()
      throws Exception {
    final List<Path> files =
        writeModule(
            "Owner.java",
            OWNER,
            "Holder.java",
            "public class Holder { public Owner owner() { return new Owner(); } }",
            "Reader.java",
            "class Reader { String read(Holder h) { return h.owner().name; } }",
            "Other.java",
            "class Other { String name; String get() { return name; } }");
    final Path owner = files.get(0);
    final Path reader = files.get(2);
    final Path other = files.get(3);

    final var pos = offsetToPosition(OWNER, OWNER.indexOf("name"));
    final LatheReferences references =
        engine.references(owner, pos.getLine(), pos.getCharacter(), 50);

    assertThat(references.references())
        .extracting(LatheLocation::uri)
        .anySatisfy(uri -> assertThat(uri).endsWith("Reader.java"))
        .noneSatisfy(uri -> assertThat(uri).endsWith("Other.java"));
    assertThat(renameAt(owner, OWNER, "name", "label").files())
        .extracting(LatheFileEdit::uri)
        .noneSatisfy(uri -> assertThat(uri).endsWith("Other.java"));
    assertThat(Files.readString(reader)).contains("h.owner().label");
    assertThat(Files.readString(other)).contains("String name;").contains("return name;");
  }

  @Test
  void referencesAndRename_enumConstantInSwitchOnUnnamedType_reachTheCaseButNotOtherConstants()
      throws Exception {
    final List<Path> files =
        writeModule(
            "Kind.java",
            KIND,
            "Holder.java",
            "public class Holder { public Kind kind() { return Kind.BETA; } }",
            "Switcher.java",
            "class Switcher { int f(Holder h) { switch (h.kind()) { case ALPHA: return 1;"
                + " default: return 0; } } }",
            "Other.java",
            "class Other { static final int ALPHA = 1; int f() { return ALPHA; } }");
    final Path kind = files.get(0);
    final Path switcher = files.get(2);
    final Path other = files.get(3);

    final var pos = offsetToPosition(KIND, KIND.indexOf("ALPHA"));
    final LatheReferences references =
        engine.references(kind, pos.getLine(), pos.getCharacter(), 50);

    assertThat(references.references())
        .extracting(LatheLocation::uri)
        .anySatisfy(uri -> assertThat(uri).endsWith("Switcher.java"))
        .noneSatisfy(uri -> assertThat(uri).endsWith("Other.java"));
    renameAt(kind, KIND, "ALPHA", "FIRST");
    assertThat(Files.readString(switcher)).contains("case FIRST:");
    assertThat(Files.readString(other)).contains("int ALPHA = 1").contains("return ALPHA;");
  }

  // Each name and source in com.example, in the order given, compiled into the mirror; then the
  // engine started. Returns the files in that order.
  private List<Path> writeModule(final String... namesAndSources) throws Exception {
    final var sources = new LinkedHashMap<String, String>();
    for (int i = 0; i < namesAndSources.length; i += 2) {
      sources.put("com/example/" + namesAndSources[i], withPackage(namesAndSources[i + 1]));
    }

    final List<Path> files =
        TestCompiler.writeMirroredModule(tmp, "module", sources, List.of(), 1_000L);
    engine = new LatheEngine(tmp);
    return files;
  }

  private static String withPackage(final String source) {
    return source.startsWith("package") ? source : "package com.example;\n%s\n".formatted(source);
  }
}
