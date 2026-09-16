package ch.so.agi.hop.raster.geotools;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BandedSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferFloat;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageWriteParam;
import org.eclipse.imagen.media.range.NoDataContainer;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.gce.geotiff.GeoTiffWriteParams;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

class RasterCoreTest {
  @TempDir Path dir;

  static Geometry box(double a, double b, double c, double d) {
    return new GeometryFactory().toGeometry(new Envelope(a, c, b, d));
  }

  static Path fixture(Path file) throws Exception {
    int size = 1024;
    WritableRaster raster =
        Raster.createWritableRaster(
            new BandedSampleModel(DataBuffer.TYPE_FLOAT, size, size, 1),
            new DataBufferFloat(size * size),
            new Point());
    java.util.Random random = new java.util.Random(123);
    for (int y = 0; y < size; y++)
      for (int x = 0; x < size; x++) raster.setSample(x, y, 0, random.nextFloat() * 1000);
    for (int y = 0; y < 3; y++)
      for (int x = 0; x < 4; x++) raster.setSample(x, y, 0, 1 + y * 4 + x);
    raster.setSample(4, 0, 0, -9999);
    raster.setSample(5, 0, 0, 0);
    var coverage =
        new GridCoverageFactory()
            .create(
                "fixture",
                raster,
                new ReferencedEnvelope(
                    2600000, 2600256, 1200000, 1200256, CRS.decode("EPSG:2056", true)));
    coverage =
        new GridCoverageFactory()
            .create(
                "fixture",
                coverage.getRenderedImage(),
                coverage.getEnvelope(),
                null,
                null,
                Map.of(NoDataContainer.GC_NODATA, new NoDataContainer(-9999)));
    GeoTiffWriteParams params = new GeoTiffWriteParams();
    params.setForceToBigTIFF(true);
    params.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
    params.setTiling(512, 512);
    params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
    params.setCompressionType("Deflate");
    var p = AbstractGridFormat.GEOTOOLS_WRITE_PARAMS.createValue();
    p.setValue(params);
    GeoTiffWriter writer = new GeoTiffWriter(file.toFile());
    try {
      writer.write(coverage, p);
    } finally {
      writer.dispose();
      coverage.dispose(true);
    }
    return file;
  }

  @Test
  void localReadsReleaseFileHandlesAndKeepCopiedSamples() throws Exception {
    Path input = fixture(dir.resolve("input.tif"));
    Raster first;
    Raster second;
    try (var source = new GeoTiffSource(new RasterDatasetRef(input.toString()))) {
      var firstRequest = new RasterReadRequest(new Rectangle(0, 0, 2, 2), 0);
      first = source.read(firstRequest);
      second = source.read(new RasterReadRequest(new Rectangle(2, 1, 2, 2), 0));
      assertThat(source.read(firstRequest)).isSameAs(first);
    }
    Path renamed = Files.move(input, dir.resolve("renamed.tif"));
    Files.delete(renamed);
    assertThat(first.getSampleDouble(0, 0, 0)).isEqualTo(1);
    assertThat(first.getSampleDouble(1, 1, 0)).isEqualTo(6);
    assertThat(second.getSampleDouble(2, 1, 0)).isEqualTo(7);
    assertThat(second.getSampleDouble(3, 2, 0)).isEqualTo(12);
  }

  @Test
  void interruptedLocalReadReleasesFileHandles() throws Exception {
    Path input = fixture(dir.resolve("interrupted.tif"));
    try (var source = new GeoTiffSource(new RasterDatasetRef(input.toString()))) {
      try {
        Thread.currentThread().interrupt();
        assertThatThrownBy(() -> source.read(new RasterReadRequest(new Rectangle(0, 0, 4, 3), 0)))
            .isInstanceOf(IOException.class)
            .hasMessage("Raster operation interrupted");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(source.cachedBytes()).isZero();
      } finally {
        Thread.interrupted();
      }
    }
    Files.delete(input);
  }

  @Test
  void localWindowStatisticsAndClipPreserveGrid() throws Exception {
    try (var source =
        new GeoTiffSource(new RasterDatasetRef(fixture(dir.resolve("in.tif")).toString()))) {
      assertThat(source.bands()).isEqualTo(1);
      assertThat(source.noData(0)).isEqualTo(-9999d);
      Raster tile = source.read(new RasterReadRequest(new Rectangle(0, 0, 4, 3), 0));
      assertThat(tile.getSampleDouble(3, 2, 0)).isEqualTo(12);
      Geometry zone = box(2600000, 1200255.25, 2600001, 1200256);
      var stats = ZonalStatistics.compute(source, zone, 0, null);
      assertThat(stats.count()).isEqualTo(12);
      assertThat(stats.mean()).isEqualTo(6.5);
      assertThat(stats.sum()).isEqualTo(78);
      assertThat(stats.stddev()).isCloseTo(Math.sqrt(143d / 12), within(1e-10));
      assertThat(ZonalStatistics.compute(source, box(0, 0, 1, 1), 0, null).status())
          .isEqualTo("NO_VALID_PIXELS");
      assertThat(ZonalStatistics.compute(source, null, 0, null).status())
          .isEqualTo("EMPTY_GEOMETRY");
      Path out = dir.resolve("clip.tif");
      RasterClip.write(source, zone, true, 0, null, out, false, () -> false);
      try (var clipped = new GeoTiffSource(new RasterDatasetRef(out.toString()))) {
        assertThat(clipped.bounds().width).isEqualTo(4);
        assertThat(clipped.bounds().height).isEqualTo(3);
        assertThat(
                clipped.read(new RasterReadRequest(clipped.bounds(), 0)).getSampleDouble(3, 2, 0))
            .isEqualTo(12);
        assertThat(ZonalStatistics.compute(clipped, zone, 0, null).mean()).isEqualTo(6.5);
      }
      assertThatThrownBy(
              () -> RasterClip.write(source, zone, true, 0, null, out, false, () -> false))
          .hasMessageContaining("exists");
      assertThat(source.cachedBytes()).isLessThanOrEqualTo(64L * 1024 * 1024);
    }
  }

  @Test
  void httpReadsOnlyRequiredRangesAndReusesTiles() throws Exception {
    byte[] file = Files.readAllBytes(CogFixture.write(dir.resolve("remote.tif")));
    AtomicLong bytes = new AtomicLong();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/cog.tif",
        e -> {
          String range = e.getRequestHeaders().getFirst("Range");
          if (range == null) {
            e.sendResponseHeaders(400, -1);
            e.close();
            return;
          }
          String[] parts = range.substring(6).split("-");
          int start = Integer.parseInt(parts[0]),
              end = Math.min(file.length - 1, Integer.parseInt(parts[1]));
          int count = end - start + 1;
          e.getResponseHeaders()
              .set("Content-Range", "bytes " + start + "-" + end + "/" + file.length);
          e.sendResponseHeaders(206, count);
          e.getResponseBody().write(file, start, count);
          e.close();
          bytes.addAndGet(count);
        });
    server.start();
    try (var source =
        new GeoTiffSource(
            new RasterDatasetRef(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/cog.tif"))) {
      var request = new RasterReadRequest(new Rectangle(0, 0, 512, 512), 0);
      assertThat(source.read(request).getSampleDouble(3, 2, 0)).isEqualTo(12);
      long before = bytes.get();
      source.read(request);
      assertThat(bytes.get()).isEqualTo(before);
      assertThat(bytes.get()).isLessThan(file.length / 2L);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsServerIgnoringRangeWithoutReadingBody() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/file",
        e -> {
          e.sendResponseHeaders(200, 0);
          e.close();
        });
    server.start();
    try {
      var reader =
          new StrictHttpRangeReader(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/file"), 1024);
      assertThatThrownBy(reader::readHeader).hasMessageContaining("HTTP 206");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void validatesLargeOffsetsAndChangingResources() throws Exception {
    long offset = (1L << 32) + 1234, total = offset + 10000;
    var changed = new java.util.concurrent.atomic.AtomicBoolean();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/large",
        e -> {
          String[] parts = e.getRequestHeaders().getFirst("Range").substring(6).split("-");
          long start = Long.parseLong(parts[0]), end = Long.parseLong(parts[1]);
          byte[] bytes = new byte[Math.toIntExact(end - start + 1)];
          java.util.Arrays.fill(bytes, (byte) 42);
          e.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + total);
          e.getResponseHeaders().set("ETag", changed.get() ? "\"v2\"" : "\"v1\"");
          e.sendResponseHeaders(206, bytes.length);
          e.getResponseBody().write(bytes);
          e.close();
        });
    server.createContext(
        "/wrong",
        e -> {
          e.getResponseHeaders().set("Content-Range", "bytes 1-1024/10000");
          e.sendResponseHeaders(206, 1024);
          e.getResponseBody().write(new byte[1024]);
          e.close();
        });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      var reader = new StrictHttpRangeReader(URI.create(base + "/large"), 1024);
      byte[] bytes = reader.read(new long[] {offset, offset + 15}).get(offset);
      assertThat(bytes).hasSize(16).containsOnly((byte) 42);
      changed.set(true);
      assertThatThrownBy(() -> reader.read(new long[] {offset + 16, offset + 31}))
          .hasMessageContaining("changed");
      var wrong = new StrictHttpRangeReader(URI.create(base + "/wrong"), 1024);
      assertThatThrownBy(wrong::readHeader).hasMessageContaining("Content-Range");
    } finally {
      server.stop(0);
    }
  }
}
