package com.github.mizosoft.methanol.internal.dec;

import static com.github.mizosoft.methanol.testutils.TestUtils.inflate;

class DeflateDecoderTest extends ZLibDecoderTest {

  @Override
  String good() {
    return "eJzlkTFuwzAMRXed4m9eegejCIrCQMcCmRmLiQTLoiHJMXT7UgrQokN6gQ5aPsnHz6+zozJkBL4WFEGmOppPx5mxJaqcMsjHoeCQtPh4A8W6SmLzdudUm2qRnRRYOSJ8xDXQytmcf2Ot4PDFoXTyJcnCEZvnmTMkNlkHRdJopmFFkNxWrRV38TNjphCaoJ1VdmOGE+2KmYa7slhJ2VEzZ36UC0fbRi40Lwclm1F8CGj0vt2cqcyuH6Tyw5ZNTGvGTbBv7ZS8tkbzwUVxtJeKWVaG7AVyBWUd+rv4Gi0Op26mHmYL4l1s3zhpywKfzYniU8BoellvRrv34bIw6acoamud+np4PmmS6lkzy7KH8TsjRGbbEY4Tv+iPkft3CT4tfwERoAh6";
  }

  @Override
  String bad() {
    return "eJzlkTFuwzAMRXed4m9eegejCIrCQMcCmRmLiQTLoiHJMXT7UgrQokN6gQ5aPsnHz6+zozJkBL4WFEGmOppPx5mxJaqcMsjHoeCQtPh4A8W6SmLzdudUm2qRnRRYOSJ8xDXQytmcf2Ot4PDFoXTyJcnCEZvnmTMkNlkHRdJopmFFkNxWrRV38TNjphCaoJ1VdmOGefyXT4G7slhJ2VEzZ36UC0fbRi40Lwclm1F8CGj0vt2cqcyuH6Tyw5ZNTGvGTbBv7ZS8tkbzwUVxtJeKWVaG7AVyBWUd+rv4Gi0Op26mHmYL4l1s3zhpywKfzYniU8BoellvRrv34bIw6acoamud+np4PmmS6lkzy7KH8TsjRGbbEY4Tv+iPkft3CT4tfwERoAh6";
  }

  @Override
  String encoding() {
    return "deflate";
  }

  @Override
  ZLibDecoder newDecoder() {
    return new DeflateDecoder();
  }

  @Override
  byte[] nativeDecode(byte[] compressed) {
    return inflate(compressed);
  }
}
