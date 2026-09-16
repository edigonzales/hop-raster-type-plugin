package ch.so.agi.hop.raster.geotools;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Opt-in acceptance test against the two public Solothurn datasets. */
class RemoteCogTest {
  @Test
  void solothurnClipAndStatistics() throws Exception {
    assumeTrue(Boolean.getBoolean("remoteCogTests"), "Enable with -DremoteCogTests=true");
    Path target = Path.of("target", "cog-smoke");
    Files.createDirectories(target);
    String[] layers = {
      "ch.swisstopo.lidar_2023.ndsm_buildings", "ch.swisstopo.swissalti3d_2025.dtm"
    };
    for (String layer : layers) {
      long started = System.nanoTime();
      String url = "https://files.geo.so.ch/" + layer + "/aktuell/" + layer + ".tif";
      try (var source = new GeoTiffSource(new RasterDatasetRef(url))) {
        assertThat(source.bands()).isEqualTo(1);
        assertThat(source.noData(0)).isEqualTo(-9999d);
        var zone = RasterCoreTest.box(2607400, 1228400, 2607500, 1228500);
        var result = ZonalStatistics.compute(source, zone, 0, null);
        assertThat(result.count()).isPositive();
        assertThat(result.mean()).isBetween(0d, 1000d);
        Path clip = target.resolve(layer + "-clip.tif");
        RasterClip.write(source, zone, true, 0, null, clip, true, () -> false);
        try (var local = new GeoTiffSource(new RasterDatasetRef(clip.toString()))) {
          var roundtrip = ZonalStatistics.compute(local, zone, 0, null);
          assertThat(roundtrip.count()).isEqualTo(result.count());
          assertThat(roundtrip.mean()).isCloseTo(result.mean(), within(1e-9));
          assertThat(local.bounds().width).isEqualTo(layer.endsWith("dtm") ? 200 : 400);
        }
        assertThat(source.httpBytesRead()).isLessThan(32L * 1024 * 1024);
        System.out.printf(
            "COG %s: count=%d mean=%.9f, %d bytes in %d HTTP requests, %.2f s%n",
            layer,
            result.count(),
            result.mean(),
            source.httpBytesRead(),
            source.httpRequests(),
            (System.nanoTime() - started) / 1e9);
      }
    }
  }
}
