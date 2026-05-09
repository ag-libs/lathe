import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;

/** A sample class used for testing the Lathe language server. */
@SuppressWarnings("unused")
public class Sample {

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  public @interface Logged {
    String value() default "";
  }

  enum Status {
    ACTIVE,
    INACTIVE
  }

  private static final String PREFIX = "item-";

  private final String name;
  private int count;

  /**
   * Creates a new sample with the given name.
   *
   * @param name the display name
   */
  public Sample(String name) {
    this.name = name;
    this.count = 0;
  }

  /**
   * Returns the display name of this sample.
   *
   * @return the name
   */
  public String getName() {
    return name;
  }

  public void increment() {
    count++;
  }

  public int getCount() {
    return count;
  }

  /**
   * Runs a computation using the given value.
   *
   * @param value the input value; must not be null
   * @return the formatted result
   * @throws NullPointerException if value is null
   */
  public String run(final String value) {
    Objects.requireNonNull(value);
    return format("result: %s", value);
  }

  public String localized() {
    return "hello".toUpperCase(ENGLISH);
  }

  public List<String> upper(List<String> items) {
    return items.stream().map(s -> s.toUpperCase()).toList();
  }

  public String overloaded(final String s) {
    return s;
  }

  public int overloaded(final int n) {
    return n;
  }

  public <T> T identity(final T value) {
    return value;
  }

  public void useOverloads() {
    overloaded("hello");
    overloaded(42);
    identity("text");
  }

  @Logged("summarize")
  public Map<String, Long> summarize(List<String> items) {
    return items.stream()
        .filter(s -> s.startsWith(PREFIX))
        .map(String::toUpperCase)
        .collect(Collectors.groupingBy(s -> s.substring(0, 1), Collectors.counting()));
  }

  public static String staticHelper(String input) {
    var trimmed = input.strip();
    final String upper = trimmed.toUpperCase(Locale.ROOT);
    return PREFIX + upper;
  }

  public Optional<String> findFirst(List<String> items, String prefix) {
    return items.stream()
        .filter(s -> s.startsWith(prefix))
        .map(s -> s.substring(prefix.length()))
        .findFirst();
  }

  public Status getStatus() {
    return Status.ACTIVE;
  }

  @Deprecated
  public String oldFormat(String value) {
    return format("old: %s", value);
  }

  public String useDeprecated(String value) {
    return oldFormat(value);
  }
}
