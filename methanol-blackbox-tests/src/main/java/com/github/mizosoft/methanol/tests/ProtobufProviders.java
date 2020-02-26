package com.github.mizosoft.methanol.tests;

import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.BodyAdapter.Encoder;
import com.github.mizosoft.methanol.adapter.protobuf.ProtobufBodyAdapterFactory;

public class ProtobufProviders {

  private ProtobufProviders() {}

  public static class EncoderProvider {

    private EncoderProvider() {}

    public static Encoder provider() {
      return ProtobufBodyAdapterFactory.createEncoder();
    }
  }

  public static class DecoderProvider {

    private DecoderProvider() {}

    public static BodyAdapter.Decoder provider() {
      return ProtobufBodyAdapterFactory.createDecoder();
    }
  }
}

