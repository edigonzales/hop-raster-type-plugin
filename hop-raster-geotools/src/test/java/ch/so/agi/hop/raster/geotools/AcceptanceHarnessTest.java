package ch.so.agi.hop.raster.geotools;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AcceptanceHarnessTest {
  @TempDir Path dir;

  @Test
  void multibandMaskAndBandOrder() throws Exception {
    BenchmarkFixtures.main(new String[] {dir.toString(), "1024", "variants"});
    MultiBandProbe.main(new String[] {dir.toString()});
  }

  @Test
  void calibratedWriterCleanupOnSuccessFailureAndAbort() throws Exception {
    WriteVolumeProbe.main(new String[] {dir.toString()});
  }
}
