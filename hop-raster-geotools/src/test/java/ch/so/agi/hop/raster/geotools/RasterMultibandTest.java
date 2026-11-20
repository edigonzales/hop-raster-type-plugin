package ch.so.agi.hop.raster.geotools;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RasterMultibandTest {
  @TempDir Path dir;

  @Test
  void multibandMaskAndBandOrder() throws Exception {
    TiledRasterFixtures.write(dir, 1024, "rgba", "palette", "numeric4");
    MultiBandProbe.main(new String[] {dir.toString()});
  }
}
