package com.github.mizosoft.methanol.tests;

import com.github.mizosoft.methanol.testutils.RegistryFileTypeDetector;
import java.nio.file.spi.FileTypeDetector;

public class RegistryFileTypeDetectorProvider {

  private RegistryFileTypeDetectorProvider() {}

  public static FileTypeDetector provider() {
    return new RegistryFileTypeDetector();
  }
}
