package com.example.app;

import com.example.core.StringUtils;

public final class Main {

  public static void main(final String[] args) {
    final var user = UserBuilder.builder().name("Alice").age(30).build();
    System.out.println(StringUtils.upper(user.name()));
  }
}
