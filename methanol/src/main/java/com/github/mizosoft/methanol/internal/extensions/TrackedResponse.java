package com.github.mizosoft.methanol.internal.extensions;

import java.net.http.HttpResponse;
import java.time.Instant;

// TODO export this as public API?

/** A response with recorded send/receive timestamps. */
public interface TrackedResponse<T> extends HttpResponse<T> {

  /** Returns the time the request resulting in this response was sent. */
  Instant timeRequestSent();

  /** Returns the time this response was received. */
  Instant timeResponseReceived();
}
