package com.example.app;

import com.example.core.Greeter;
import com.example.core.StringUtils;

public final class Main {

  // Lambda and method-reference implementations of the Greeter SAM, typed so the interface is
  // spelled: go-to-implementation on Greeter.greet resolves both alongside the named *Greeter
  // classes.
  static final Greeter LAMBDA_GREETER = name -> "Hi " + name;
  static final Greeter METHOD_REF_GREETER = StringUtils::upper;

  // A Greeter-typed parameter so a caller can pass a bare lambda without naming the interface.
  public static String greetWith(final Greeter greeter, final String name) {
    return greeter.greet(name);
  }

  public static void main(final String[] args) {
    final User user = UserBuilder.builder().name("Alice").age(30).build();
    System.out.println(StringUtils.upper(user.name()));
  }
}
