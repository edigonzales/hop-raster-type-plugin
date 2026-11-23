package ch.so.agi.hop.raster.geotools;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.so.agi.hop.raster.RasterOperation;
import ch.so.agi.hop.raster.RasterReference;
import ch.so.agi.hop.raster.RasterWriteOptions;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Optional check against the two public Solothurn COGs. */
class RemoteCogTest {
  @TempDir Path target;

  @Test
  void solothurnClipAndStatistics() throws Exception {
    assumeTrue(Boolean.getBoolean("remoteCogTests"), "Enable with -DremoteCogTests=true");
    String[] layers = {
      "ch.swisstopo.lidar_2023.ndsm_buildings", "ch.swisstopo.swissalti3d_2025.dtm"
    };
    for (String layer : layers) {
      String url = "https://files.geo.so.ch/" + layer + "/aktuell/" + layer + ".tif";
      try (var backend = new GeoToolsRasterBackend()) {
        var value = backend.describe(url);
        try (var session = backend.session(value, () -> false)) {
          var source = (GeoTiffSource) session.source();
          assertThat(source.bands()).isEqualTo(1);
          assertThat(source.noData(0)).isEqualTo(-9999d);
          var zone = RasterCoreTest.box(2607400, 1228400, 2607500, 1228500);
          var result = ZonalStatistics.compute(source, zone, 0, null);
          assertThat(result.count()).isPositive();
          assertThat(result.mean()).isBetween(0d, 1000d);
          Path clip = target.resolve(layer + "-clip.tif");
          var data = backend.derive(value, new RasterOperation.Clip(
              "clip", zone.toText(), true, java.util.List.of(0), null));
          backend.write(data, clip, true, () -> false,
              RasterWriteOptions.geoTiff("Deflate"), ignored -> {});
          try (var local = new GeoTiffSource(new RasterReference(clip.toString(), null))) {
            var roundtrip = ZonalStatistics.compute(local, zone, 0, null);
            assertThat(roundtrip.count()).isEqualTo(result.count());
            assertThat(roundtrip.mean()).isCloseTo(result.mean(), within(1e-9));
            assertThat(local.bounds().width).isEqualTo(layer.endsWith("dtm") ? 200 : 400);
          }
          assertThat(source.httpBytesRead()).isLessThan(32L * 1024 * 1024);
        }
      }
    }
  }
}
