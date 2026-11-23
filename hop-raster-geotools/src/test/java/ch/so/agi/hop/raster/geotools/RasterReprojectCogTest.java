package ch.so.agi.hop.raster.geotools;

import static org.assertj.core.api.Assertions.*;

import ch.so.agi.hop.raster.RasterOperation;
import ch.so.agi.hop.raster.RasterReference;
import ch.so.agi.hop.raster.RasterWriteOptions;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.DataBuffer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RasterReprojectCogTest {
  @TempDir Path dir;

  @Test
  void resamplesOriginalResolutionOverHttpAndReusesCachedSource() throws Exception {
    byte[] file = Files.readAllBytes(CogFixture.write(dir.resolve("input.tif")));
    var bytes = new AtomicLong();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/raster.tif",
        exchange -> {
          String range = exchange.getRequestHeaders().getFirst("Range");
          if (range == null) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
          }
          String[] parts = range.substring(6).split("-");
          int start = Integer.parseInt(parts[0]),
              end = Math.min(file.length - 1, Integer.parseInt(parts[1]));
          exchange
              .getResponseHeaders()
              .set("Content-Range", "bytes " + start + "-" + end + "/" + file.length);
          exchange.getResponseHeaders().set("ETag", "\"fixture\"");
          exchange.sendResponseHeaders(206, end - start + 1);
          exchange.getResponseBody().write(file, start, end - start + 1);
          bytes.addAndGet(end - start + 1);
          exchange.close();
        });
    server.start();
    try (var backend = new GeoToolsRasterBackend()) {
      var input = backend.describe("http://127.0.0.1:" + server.getAddress().getPort() + "/raster.tif");
      var operation = new RasterOperation.Reproject("reproject", "EPSG:2056", .5, .5,
          java.util.List.of(2600000d, 1200255.5, 2600001d, 1200256d),
          "BILINEAR", "FLOAT32", null, -1000d);
      var value = backend.derive(input, operation);
      long first = 0;
      for (int iteration = 0; iteration < 2; iteration++) {
        Path target = dir.resolve("output-" + iteration + ".tif");
        backend.write(value, target, false, () -> false,
            RasterWriteOptions.geoTiff("Deflate"), ignored -> {});
        try (var result = new GeoTiffSource(new RasterReference(target.toString(), null))) {
          assertThat(result.dataType()).isEqualTo(DataBuffer.TYPE_FLOAT);
          assertThat(result.noData(0)).isEqualTo(-1000d);
          assertThat(RasterReprojectTest.band(result, 0)).containsExactly(3.5, 5.5);
        }
        if (iteration == 0) first = bytes.get();
        else assertThat(bytes.get()).isEqualTo(first);
      }
      assertThat(bytes.get()).isLessThan(file.length / 2L);
      try (var source = backend.session(input, () -> false)) {
        assertThat(((GeoTiffSource) source.source()).cachedBytes())
            .isLessThanOrEqualTo(64L * 1024 * 1024);
      }
    } finally {
      server.stop(0);
    }
  }
}
