package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.VariableElement;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Sample.java key positions (0-based):
//  15: public class Sample {
//  38: public Sample(String name) {             @param name
//  48: public String getName() {                @return
//  52: public void increment() {                no doc
//  67: public String run(final String value) {  @param value  @return  @throws
//  76: public List<String> upper(List<String> items)  items=42
class JavadocExtractorTest extends SampleFixture {

  @Nested
  class SameFile {

    @Test
    void classDoc_returnsDescription() {
      final var element = elementAt(15, 13);
      assertThat(element).isNotNull();

      final var result = JavadocExtractor.extract(trees, element, List.of());

      assertThat(result).isPresent();
      assertThat(result.get()).contains("sample class");
      assertThat(result.get()).contains("testing");
    }

    @Test
    void constructorDoc_returnsDescriptionAndParam() {
      final var element = elementAt(38, 9);
      assertThat(element).isNotNull();
      assertThat(element.getKind()).isEqualTo(ElementKind.CONSTRUCTOR);

      final var result = JavadocExtractor.extract(trees, element, List.of());

      assertThat(result).isPresent();
      assertThat(result.get()).contains("Creates a new sample");
      assertThat(result.get()).contains("**Parameters:**");
      assertThat(result.get()).contains("`name`");
      assertThat(result.get()).contains("display name");
    }

    @Test
    void methodDoc_returnsDescriptionReturnAndThrows() {
      final var element = elementAt(67, 16);
      assertThat(element).isNotNull();
      assertThat(element.getKind()).isEqualTo(ElementKind.METHOD);

      final var result = JavadocExtractor.extract(trees, element, List.of());

      assertThat(result).isPresent();
      assertThat(result.get()).contains("Runs a computation");
      assertThat(result.get()).contains("**Parameters:**");
      assertThat(result.get()).contains("`value`");
      assertThat(result.get()).contains("input value");
      assertThat(result.get()).contains("**Returns:**");
      assertThat(result.get()).contains("formatted result");
      assertThat(result.get()).contains("**Throws**");
      assertThat(result.get()).contains("NullPointerException");
    }

    @Test
    void methodWithReturnOnly_returnsDescriptionAndReturn() {
      final var element = elementAt(48, 16);
      assertThat(element).isNotNull();

      final var result = JavadocExtractor.extract(trees, element, List.of());

      assertThat(result).isPresent();
      assertThat(result.get()).contains("Returns the display name");
      assertThat(result.get()).contains("**Returns:**");
      assertThat(result.get()).contains("the name");
    }

    @Test
    void methodWithoutDoc_returnsEmpty() {
      final var element = elementAt(52, 14);
      assertThat(element).isNotNull();
      assertThat(element.getSimpleName().toString()).isEqualTo("increment");

      assertThat(JavadocExtractor.extract(trees, element, List.of())).isEmpty();
    }

    @Test
    void nullElement_returnsEmpty() {
      assertThat(JavadocExtractor.extract(trees, null, List.of())).isEmpty();
    }
  }

  @Nested
  class ParamExtraction {

    @Test
    void paramWithDoc_returnsParamTag() {
      // "value" parameter in run()'s declaration at line 67, col 33
      final var param = (VariableElement) elementAt(67, 33);
      assertThat(param).isNotNull();
      assertThat(param.getSimpleName().toString()).isEqualTo("value");

      final var result = JavadocExtractor.extractParam(trees, param, List.of());

      assertThat(result).isPresent();
      assertThat(result.get()).contains("input value");
      assertThat(result.get()).doesNotContain("**Parameters:**");
    }

    @Test
    void constructorParam_returnsParamTag() {
      // "name" parameter in constructor declaration at line 38, col 23
      final var param = (VariableElement) elementAt(38, 23);
      assertThat(param).isNotNull();
      assertThat(param.getSimpleName().toString()).isEqualTo("name");

      final var result = JavadocExtractor.extractParam(trees, param, List.of());

      assertThat(result).isPresent();
      assertThat(result.get()).contains("display name");
    }

    @Test
    void methodWithoutParamDoc_returnsEmpty() {
      // "items" parameter in upper(List<String> items) at line 76, col 42 — no Javadoc on method
      final var param = (VariableElement) elementAt(76, 42);
      assertThat(param).isNotNull();
      assertThat(param.getSimpleName().toString()).isEqualTo("items");

      assertThat(JavadocExtractor.extractParam(trees, param, List.of())).isEmpty();
    }
  }

  @Nested
  class ReactorFallback {

    @Test
    void extractsDocFromReactorSource(@TempDir final Path reactorSrcRoot) throws IOException {
      final var greeterSrc = reactorSrcRoot.resolve("Greeter.java");
      Files.writeString(
          greeterSrc,
          """
          /**
           * A greeter class.
           */
          public class Greeter {

            /**
             * Greets with a message.
             *
             * @param msg the message to display
             * @return the greeting string
             */
            public String greet(String msg) {
              return "Hello: " + msg;
            }
          }
          """);

      final var compiler = ToolProvider.getSystemJavaCompiler();
      final var fm = compiler.getStandardFileManager(null, null, null);
      final var jfo = fm.getJavaFileObjects(greeterSrc).iterator().next();
      final var task = (JavacTask) compiler.getTask(null, fm, null, null, null, List.of(jfo));
      task.parse();
      task.analyze();
      final var greeterType = task.getElements().getTypeElement("Greeter");
      final var greetMethod =
          greeterType.getEnclosedElements().stream()
              .filter(e -> e.getSimpleName().toString().equals("greet"))
              .findFirst()
              .orElseThrow();

      final var result =
          JavadocExtractor.extract(DocTrees.instance(task), greetMethod, List.of(reactorSrcRoot));

      assertThat(result).isPresent();
      assertThat(result.get()).contains("Greets with a message");
      assertThat(result.get()).contains("**Parameters:**");
      assertThat(result.get()).contains("`msg`");
      assertThat(result.get()).contains("message to display");
      assertThat(result.get()).contains("**Returns:**");
      assertThat(result.get()).contains("greeting string");
    }

    @Test
    void noSourceFile_returnsEmpty(@TempDir final Path emptyRoot) throws IOException {
      final var src = tmp.resolve("Foo.java");
      Files.writeString(src, "public class Foo { public void bar() {} }");
      final var compiler = ToolProvider.getSystemJavaCompiler();
      final var fm = compiler.getStandardFileManager(null, null, null);
      final var jfo = fm.getJavaFileObjects(src).iterator().next();
      final var task = (JavacTask) compiler.getTask(null, fm, null, null, null, List.of(jfo));
      task.parse();
      task.analyze();
      final var fooType = task.getElements().getTypeElement("Foo");
      final var barMethod =
          fooType.getEnclosedElements().stream()
              .filter(e -> e.getSimpleName().toString().equals("bar"))
              .findFirst()
              .orElseThrow();

      assertThat(JavadocExtractor.extract(DocTrees.instance(task), barMethod, List.of(emptyRoot)))
          .isEmpty();
    }
  }

  // ---------------------------------------------------------------------------

  private Element elementAt(final int line, final int character) {
    final var offset = SourceLocator.toOffset(cu, line, character);
    final var path = SourceLocator.pathAt(trees, cu, offset);
    return SourceLocator.elementAt(trees, path);
  }
}
