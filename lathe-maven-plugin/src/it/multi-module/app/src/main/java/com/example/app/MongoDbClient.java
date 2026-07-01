package com.example.app;

import com.example.core.DbClient;
import com.example.core.DbClientBase;

public final class MongoDbClient extends DbClientBase implements DbClient {
  @Override
  public String dbType() {
    return "mongo";
  }
}
