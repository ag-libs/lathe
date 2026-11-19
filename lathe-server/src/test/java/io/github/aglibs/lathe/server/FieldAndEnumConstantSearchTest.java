package io.github.aglibs.lathe.server;

import static io.github.aglibs.lathe.analysis.Positions.offsetToPosition;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.engine.LatheFileEdit;
import io.github.aglibs.lathe.server.engine.LatheReference;
import io.github.aglibs.lathe.server.engine.LatheReferences;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.eclipse.lsp4j.Position;
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
        engine.references(owner, pos.getLine(), pos.getCharacter(), 50, ANY_ACCESS).value();

    assertThat(references.references())
        .extracting(ref -> ref.location().uri())
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
        engine.references(kind, pos.getLine(), pos.getCharacter(), 50, ANY_ACCESS).value();

    assertThat(references.references())
        .extracting(ref -> ref.location().uri())
        .anySatisfy(uri -> assertThat(uri).endsWith("Switcher.java"))
        .noneSatisfy(uri -> assertThat(uri).endsWith("Other.java"));
    renameAt(kind, KIND, "ALPHA", "FIRST");
    assertThat(Files.readString(switcher)).contains("case FIRST:");
    assertThat(Files.readString(other)).contains("int ALPHA = 1").contains("return ALPHA;");
  }

  // Each name and source in com.example, in the order given, compiled into the mirror; then the
  // engine started. Returns the files in that order.
  // Each use of a field says whether it writes it (assigned, compound-assigned, incremented) or
  // reads it, and the access filter keeps one kind; a method's calls are neither.
  @Test
  void references_fieldUses_taggedReadOrWrite_filteredByAccess() throws Exception {
    final List<Path> files =
        writeModule(
            "Counter.java",
            """
            class Counter {
              int count;
              void use() {
                count = 1;
                count += 2;
                count++;
                System.out.println(count);
                if (count > 3) { reset(); }
              }
              void reset() {}
            }
            """);
    final Path counter = files.get(0);
    final String source = Files.readString(counter);
    final var field = offsetToPosition(source, source.indexOf("count;"));

    assertThat(accesses(counter, field, ANY_ACCESS))
        .containsExactlyInAnyOrder(
            LatheReference.Access.WRITE,
            LatheReference.Access.WRITE,
            LatheReference.Access.WRITE,
            LatheReference.Access.READ,
            LatheReference.Access.READ);
    assertThat(accesses(counter, field, Set.of(LatheReference.Access.WRITE)))
        .hasSize(3)
        .containsOnly(LatheReference.Access.WRITE);
    assertThat(accesses(counter, field, Set.of(LatheReference.Access.READ)))
        .hasSize(2)
        .containsOnly(LatheReference.Access.READ);
    final var method = offsetToPosition(source, source.indexOf("reset() {}"));
    assertThat(accesses(counter, method, ANY_ACCESS)).containsOnly(LatheReference.Access.OTHER);
    assertThat(accesses(counter, method, Set.of(LatheReference.Access.WRITE))).isEmpty();
  }

  // MC-93: the result counts the uses the access filter left out, so an empty answer for a used
  // symbol (a method's calls, a field never written) can say it has uses. The declaration is no
  // use: an unused method counts none.
  @Test
  void references_accessFilter_countsTheUsesItLeftOutButNotTheDeclaration() throws Exception {
    final List<Path> files =
        writeModule(
            "Total.java",
            """
            class Total {
              final int limit = 3;
              int total() { return limit; }
              int twice() { return total() + total(); }
              void unused() {}
            }
            """);
    final Path total = files.get(0);
    final String source = Files.readString(total);
    final var method = offsetToPosition(source, source.indexOf("total() {"));
    final var field = offsetToPosition(source, source.indexOf("limit = 3"));
    final var unused = offsetToPosition(source, source.indexOf("unused()"));
    final Set<LatheReference.Access> write = Set.of(LatheReference.Access.WRITE);

    assertThat(references(total, method, write).filteredOut()).isEqualTo(2);
    assertThat(references(total, field, write).filteredOut()).isEqualTo(1);
    assertThat(references(total, unused, write).filteredOut()).isZero();
    assertThat(references(total, method, ANY_ACCESS).filteredOut()).isZero();
  }

  private List<LatheReference.Access> accesses(
      final Path file, final Position pos, final Set<LatheReference.Access> kept) {
    final LatheReferences references = references(file, pos, kept);
    assertThat(references.total()).isEqualTo(references.references().size());
    return references.references().stream().map(LatheReference::access).toList();
  }

  private LatheReferences references(
      final Path file, final Position pos, final Set<LatheReference.Access> kept) {
    return engine.references(file, pos.getLine(), pos.getCharacter(), 50, kept).value();
  }

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
