package com.example.app;

/**
 * An argument-position Greeter implementation: the lambda is passed straight to {@code greetWith},
 * so this source never names {@code Greeter}. Go-to-implementation on {@code Greeter.greet} must
 * still find it (via the compiled-bytecode lambda index, not the identifier index).
 */
public final class Dispatcher {

  public String dispatch(final String name) {
    return Main.greetWith(who -> "Hey " + who, name);
  }
}
