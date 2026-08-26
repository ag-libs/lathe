package io.github.aglibs.lathe.server.debug;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.server.analysis.AttributedExpression;
import io.github.aglibs.lathe.server.analysis.SourceAnalysisSession;
import io.github.aglibs.lathe.server.analysis.TempSourceCompiler;
import java.util.function.BiConsumer;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.junit.jupiter.api.Test;

final class JvmDescriptorTest {

  private static final String SRC =
      """
      class T {
        void m() {
          int here = 0;
        }
      }
      """;

  // Any real javac Types/Elements works; attribute a trivial expression to obtain them.
  private static void withModel(final BiConsumer<Types, Elements> body) {
    try (var session = new SourceAnalysisSession(new TempSourceCompiler())) {
      final AttributedExpression attr =
          session.attributeExpression(TempSourceCompiler.TEST_URI, SRC, 3, "1").orElseThrow();
      body.accept(attr.types(), attr.elements());
    }
  }

  @Test
  void of_primitivesAndVoid_useSingleLetterCodes() {
    withModel(
        (types, elements) -> {
          assertThat(JvmDescriptor.of(types.getPrimitiveType(TypeKind.INT), types, elements))
              .isEqualTo("I");
          assertThat(JvmDescriptor.of(types.getPrimitiveType(TypeKind.LONG), types, elements))
              .isEqualTo("J");
          assertThat(JvmDescriptor.of(types.getPrimitiveType(TypeKind.BOOLEAN), types, elements))
              .isEqualTo("Z");
          assertThat(JvmDescriptor.of(types.getPrimitiveType(TypeKind.DOUBLE), types, elements))
              .isEqualTo("D");
          assertThat(JvmDescriptor.of(types.getNoType(TypeKind.VOID), types, elements))
              .isEqualTo("V");
        });
  }

  @Test
  void of_referenceAndArrays_useLAndBrackets() {
    withModel(
        (types, elements) -> {
          final TypeMirror string = elements.getTypeElement("java.lang.String").asType();
          assertThat(JvmDescriptor.of(string, types, elements)).isEqualTo("Ljava/lang/String;");

          final TypeMirror intArray = types.getArrayType(types.getPrimitiveType(TypeKind.INT));
          assertThat(JvmDescriptor.of(intArray, types, elements)).isEqualTo("[I");

          final TypeMirror stringArray2d = types.getArrayType(types.getArrayType(string));
          assertThat(JvmDescriptor.of(stringArray2d, types, elements))
              .isEqualTo("[[Ljava/lang/String;");
        });
  }

  @Test
  void of_nestedType_usesDollarInternalName() {
    withModel(
        (types, elements) ->
            assertThat(
                    JvmDescriptor.of(
                        elements.getTypeElement("java.util.Map.Entry").asType(), types, elements))
                .isEqualTo("Ljava/util/Map$Entry;"));
  }

  @Test
  void of_genericType_erasesToRaw() {
    withModel(
        (types, elements) ->
            assertThat(
                    JvmDescriptor.of(
                        elements.getTypeElement("java.util.List").asType(), types, elements))
                .isEqualTo("Ljava/util/List;"));
  }
}
