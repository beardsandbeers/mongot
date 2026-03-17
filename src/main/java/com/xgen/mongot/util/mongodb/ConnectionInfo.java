package com.xgen.mongot.util.mongodb;

import com.mongodb.ConnectionString;
import java.util.Optional;
import javax.net.ssl.SSLContext;

public record ConnectionInfo(ConnectionString uri, Optional<SSLContext> sslContext) {

  public ConnectionInfo(ConnectionString uri) {
    this(uri, Optional.empty());
  }
}
