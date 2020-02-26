package com.github.mizosoft.methanol.tests;

import com.github.mizosoft.methanol.BodyAdapter.Decoder;
import com.github.mizosoft.methanol.testutils.StringDecoder;

public class StringDecoderProvider {

  private StringDecoderProvider() {}

  public static Decoder provider() {
    return new StringDecoder();
  }
}
