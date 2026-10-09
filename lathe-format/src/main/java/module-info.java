module io.github.aglibs.lathe.format {
  requires com.google.common;
  requires java.compiler;
  requires jdk.compiler;
  requires static auto.value.annotations;
  requires static com.google.errorprone.annotations;
  requires static org.jspecify;

  exports io.github.aglibs.lathe.format.gjf.java;
}
