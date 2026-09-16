package ch.so.agi.hop.raster.geotools;

import static org.assertj.core.api.Assertions.*;

import ch.so.agi.hop.raster.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RasterResourceTest {
  @TempDir Path dir;

  @Test
  void sourceSwitchClosesSessionsAndAllowsReopeningValues() throws Exception {
    Path a = RasterCoreTest.fixture(dir.resolve("a.tif"));
    Path b = RasterCoreTest.fixture(dir.resolve("b.tif"));
    try (var backend = new GeoToolsRasterBackend()) {
      var first = backend.describe(a.toString());
      var session = backend.session(first, () -> false);
      var raw = (GeoTiffSource) AcceptanceProbe.field(backend, "source");
      session.read(new RasterWindow(0, 0, 16, 16), 0);
      backend.describe(b.toString());
      assertThatThrownBy(session::source).isInstanceOf(IllegalStateException.class);
      assertThat(raw.cachedBytes()).isZero();
      AcceptanceProbe.resources(backend, 0);
      try (var reopened = backend.session(first, () -> false)) {
        reopened.read(new RasterWindow(0, 0, 16, 16), 0);
      }
      backend.close();
      AcceptanceProbe.resources(backend, 0);
    }
  }

  @Test
  void operationsShareCachesAcrossTwoFourAndEightStages() throws Exception {
    Path input = RasterCoreTest.fixture(dir.resolve("chain.tif"));
    try (var backend = new GeoToolsRasterBackend()) {
      var value = backend.describe(input.toString());
      for (int i = 1; i <= 8; i++) {
        value =
            backend.derive(
                value,
                new RasterOperation.Reproject(
                    "stage-" + i,
                    "EPSG:2056",
                    .5,
                    .5,
                    List.of(),
                    "BILINEAR",
                    "FLOAT32",
                    null,
                    null));
        if (i == 2 || i == 4 || i == 8)
          try (var session = backend.session(value, () -> false)) {
            session.read(new RasterWindow(0, 0, 32, 32), 0);
            var cache =
                (org.eclipse.imagen.TileCache) AcceptanceProbe.field(session, "outputCache");
            var operations =
                (org.eclipse.imagen.TileCache) AcceptanceProbe.field(session, "operationCache");
            assertThat(cache.getMemoryCapacity()).isEqualTo(16L * 1024 * 1024);
            assertThat(operations.getMemoryCapacity()).isEqualTo(8L * 1024 * 1024);
            AcceptanceProbe.resources(backend, 1);
          }
        AcceptanceProbe.resources(backend, 0);
      }
    }
  }
}
