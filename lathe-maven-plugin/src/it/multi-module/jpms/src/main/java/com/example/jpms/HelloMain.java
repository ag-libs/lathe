package com.example.jpms;

public final class HelloMain {

  private HelloMain() {}

  public static void main(final String[] args) {
    System.out.println(Hello.greet("Main"));
  }
}
