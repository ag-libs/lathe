package io.github.aglibs.lathe.core.typeindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aglibs.lathe.core.Json;
import java.util.List;
import org.junit.jupiter.api.Test;

class TypeIndexFileTest {

  @Test
  void fromJson_dependencyOrigin_roundTrips() {
    final TypeIndexFile file =
        new TypeIndexFile(
            "1",
            TypeIndexOrigin.dependency(
                new DependencyTypeIndexOrigin(
                    "com.google.guava:guava:32.0.0-jre", "/repo/guava.jar", 42, 1234)),
            List.of(
                new TypeIndexEntry(
                    "ImmutableList",
                    "com.google.common.collect.ImmutableList",
                    "com.google.common.collect",
                    TypeKind.CLASS)));

    final TypeIndexFile roundTrip = Json.fromJson(Json.toJson(file), TypeIndexFile.class);

    assertThat(roundTrip).isEqualTo(file);
  }

  @Test
  void constructor_dependencyOriginWithJdkBranch_throws() {
    final DependencyTypeIndexOrigin dependency =
        new DependencyTypeIndexOrigin("com.example:lib:1", "/repo/lib.jar", 1, 2);
    final JdkTypeIndexOrigin jdk = new JdkTypeIndexOrigin("/jdk", "Vendor", "21");

    assertThatThrownBy(
            () -> new TypeIndexOrigin(TypeIndexOriginKind.DEPENDENCY, dependency, jdk, null))
        .hasMessageContaining("jdk");
  }

  @Test
  void constructor_reactorOrigin_requiresSourceTree() {
    assertThatThrownBy(
            () -> new ReactorTypeIndexOrigin("app", null, "/workspace/.lathe/app/classes"))
        .hasMessageContaining("sourceTree");
  }
}
