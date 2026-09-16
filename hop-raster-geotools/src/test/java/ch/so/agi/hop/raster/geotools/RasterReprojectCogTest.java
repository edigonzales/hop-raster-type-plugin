package ch.so.agi.hop.raster.geotools;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
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
    try (var source =
        new GeoTiffSource(
            new RasterDatasetRef(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/raster.tif"))) {
      long first = 0;
      for (int iteration = 0; iteration < 2; iteration++) {
        Path target = dir.resolve("output-" + iteration + ".tif");
        var request =
            new RasterReprojectRequest(
                "EPSG:2056",
                .5,
                .5,
                new RasterReprojectRequest.Extent(2600000, 1200255.5, 2600001, 1200256),
                RasterReprojectRequest.Interpolation.BILINEAR,
                RasterReprojectRequest.OutputType.AUTO,
                null,
                null,
                target,
                false);
        RasterReproject.write(source, request, () -> false);
        try (var result = new GeoTiffSource(new RasterDatasetRef(target.toString()))) {
          assertThat(RasterReprojectTest.band(result, 0)).containsExactly(3.5, 5.5);
        }
        if (iteration == 0) first = bytes.get();
        else assertThat(bytes.get()).isEqualTo(first);
      }
      assertThat(bytes.get()).isLessThan(file.length / 2L);
      assertThat(source.cachedBytes()).isLessThanOrEqualTo(64L * 1024 * 1024);
    } finally {
      server.stop(0);
    }
  }
}
