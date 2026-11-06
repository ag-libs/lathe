package com.example.app;

import com.example.json.Json;
import com.example.json.shaded.gson.Gson;

// Compiles only against json's shaded jar: Gson is there under its relocated package.
public final class App {

  private App() {}

  public static String encode(final Object value) {
    return new Gson().toJson(value) + Json.quote("done");
  }
}
