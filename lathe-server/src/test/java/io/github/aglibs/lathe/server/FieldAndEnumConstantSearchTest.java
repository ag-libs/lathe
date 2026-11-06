package io.github.aglibs.lathe.server;

import static io.github.aglibs.lathe.analysis.SourceLocator.offsetToPosition;
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
// labelling a switch over one: Holder exposes the type, so the files spelling only the member's
// name are searched too.
class FieldAndEnumConstantSearchTest extends LatheEngineTestBase {

  private static final String OWNER =
      """
      package com.example;

      public class Owner {
        public String name;
      }
      """;

  @Test
  void references_fieldReadThroughUnnamedType_findsTheRead() throws Exception {
    final var sources = new LinkedHashMap<String, String>();
    sources.put("com/example/Owner.java", OWNER);
    sources.put(
        "com/example/Holder.java",
        "package com.example;\npublic class Holder { public Owner owner() { return new Owner(); } }\n");
    sources.put(
        "com/example/Reader.java",
        "package com.example;\nclass Reader { String read(Holder h) { return h.owner().name; } }\n");
    final List<Path> files =
        TestCompiler.writeMirroredModule(tmp, "module", sources, List.of(), 1_000L);
    engine = new LatheEngine(tmp);

    final var pos = offsetToPosition(OWNER, OWNER.indexOf("name"));
    final LatheReferences references =
        engine.references(files.get(0), pos.getLine(), pos.getCharacter(), 50);

    assertThat(references.references())
        .extracting(LatheLocation::uri)
        .anySatisfy(uri -> assertThat(uri).endsWith("Reader.java"));
  }

  @Test
  void rename_fieldReadThroughUnnamedType_renamesTheRead() throws Exception {
    final var sources = new LinkedHashMap<String, String>();
    sources.put("com/example/Owner.java", OWNER);
    sources.put(
        "com/example/Holder.java",
        "package com.example;\npublic class Holder { public Owner owner() { return new Owner(); } }\n");
    sources.put(
        "com/example/Reader.java",
        "package com.example;\nclass Reader { String read(Holder h) { return h.owner().name; } }\n");
    final List<Path> files =
        TestCompiler.writeMirroredModule(tmp, "module", sources, List.of(), 1_000L);
    engine = new LatheEngine(tmp);

    final var rename = renameAt(files.get(0), OWNER, "name", "label");

    assertThat(rename.files())
        .extracting(LatheFileEdit::uri)
        .anySatisfy(uri -> assertThat(uri).endsWith("Reader.java"));
    assertThat(Files.readString(files.get(2))).contains("h.owner().label");
  }

  @Test
  void referencesAndRename_enumConstantInSwitchOnUnnamedType_reachTheCase() throws Exception {
    final String kind = "package com.example;\npublic enum Kind { ALPHA, BETA }\n";
    final var sources = new LinkedHashMap<String, String>();
    sources.put("com/example/Kind.java", kind);
    sources.put(
        "com/example/Holder.java",
        "package com.example;\npublic class Holder { public Kind kind() { return Kind.ALPHA; } }\n");
    sources.put(
        "com/example/Switcher.java",
        "package com.example;\nclass Switcher { int f(Holder h) { switch (h.kind()) { case ALPHA: return 1; default: return 0; } } }\n");
    final List<Path> files =
        TestCompiler.writeMirroredModule(tmp, "module", sources, List.of(), 1_000L);
    engine = new LatheEngine(tmp);

    final var pos = offsetToPosition(kind, kind.indexOf("ALPHA"));
    final LatheReferences references =
        engine.references(files.get(0), pos.getLine(), pos.getCharacter(), 50);

    assertThat(references.references())
        .extracting(LatheLocation::uri)
        .anySatisfy(uri -> assertThat(uri).endsWith("Switcher.java"));
    renameAt(files.get(0), kind, "ALPHA", "FIRST");
    assertThat(Files.readString(files.get(2))).contains("case FIRST:");
  }
}
