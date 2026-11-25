package io.github.aglibs.lathe.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aglibs.lathe.analysis.ReferenceTarget;
import io.github.aglibs.lathe.analysis.ReferenceTarget.SearchScope;
import java.util.List;
import javax.lang.model.element.ElementKind;
import org.junit.jupiter.api.Test;

class BeanPropertiesTest {

  // MC-109: the name a template or a framework binds, by java.beans.Introspector's rules.
  @Test
  void name_fieldAndAccessors_theirJavaBeansProperty() {
    assertThat(BeanProperties.name(field("city"))).hasValue("city");
    assertThat(BeanProperties.name(method("getCity", "()"))).hasValue("city");
    assertThat(BeanProperties.name(method("isActive", "()"))).hasValue("active");
    assertThat(BeanProperties.name(method("setCity", "(java.lang.String)"))).hasValue("city");
    assertThat(BeanProperties.name(method("getURL", "()"))).hasValue("URL");
    assertThat(BeanProperties.name(method("getX", "()"))).hasValue("x");
  }

  // A method that is no accessor, by its name or its parameters, and a type, name no property.
  @Test
  void name_notAnAccessor_none() {
    assertThat(BeanProperties.name(method("getter", "()"))).isEmpty();
    assertThat(BeanProperties.name(method("issue", "()"))).isEmpty();
    assertThat(BeanProperties.name(method("get", "()"))).isEmpty();
    assertThat(BeanProperties.name(method("getCity", "(int)"))).isEmpty();
    assertThat(BeanProperties.name(method("setCity", "()"))).isEmpty();
    assertThat(BeanProperties.name(method("setCity", "(int,int)"))).isEmpty();
    assertThat(BeanProperties.name(method("compute", "()"))).isEmpty();
    // A constant, or a static factory such as getInstance(), is no property.
    assertThat(
            BeanProperties.name(
                new ReferenceTarget(
                    ElementKind.FIELD,
                    "com.example.Owner",
                    "MAX_SIZE",
                    null,
                    SearchScope.REACTOR_MODULES,
                    List.of(),
                    false,
                    true,
                    -1,
                    null)))
        .isEmpty();
    assertThat(
            BeanProperties.name(
                ReferenceTarget.ofExecutable(
                    ElementKind.METHOD,
                    "com.example.Owner",
                    "getInstance",
                    "()",
                    SearchScope.REACTOR_MODULES,
                    true)))
        .isEmpty();
    assertThat(
            BeanProperties.name(
                new ReferenceTarget(
                    ElementKind.CLASS,
                    "com.example.Owner",
                    "Owner",
                    null,
                    SearchScope.REACTOR_MODULES,
                    List.of(),
                    false,
                    false,
                    -1,
                    null)))
        .isEmpty();
  }

  private static ReferenceTarget field(final String name) {
    return new ReferenceTarget(
        ElementKind.FIELD,
        "com.example.Owner",
        name,
        null,
        SearchScope.DECLARING_FILE,
        List.of(),
        false,
        false,
        -1,
        null);
  }

  private static ReferenceTarget method(final String name, final String descriptor) {
    return ReferenceTarget.ofExecutable(
        ElementKind.METHOD,
        "com.example.Owner",
        name,
        descriptor,
        SearchScope.REACTOR_MODULES,
        false);
  }
}
