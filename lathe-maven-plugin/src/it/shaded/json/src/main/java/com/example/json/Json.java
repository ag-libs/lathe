package com.example.json;

import com.google.gson.Gson;

public final class Json {

  private Json() {}

  public static String quote(final String text) {
    return new Gson().toJson(text);
  }
}
