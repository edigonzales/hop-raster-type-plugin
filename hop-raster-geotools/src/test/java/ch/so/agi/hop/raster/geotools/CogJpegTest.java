package ch.so.agi.hop.raster.geotools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.so.agi.hop.raster.RasterWriteOptions;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.BandedSampleModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** COG output with baseline JPEG (YCbCr for RGB, grayscale for single-band byte). */
class CogJpegTest {
  @TempDir Path dir;

  private static RasterWriteOptions jpeg(int quality) {
    return new RasterWriteOptions(
        RasterWriteOptions.Format.COG,
        RasterWriteOptions.Overviews.AUTO,
        RasterWriteOptions.Resampling.AVERAGE,
        512,
        "JPEG",
        quality);
  }

  @Test
  void exposesJpegAsCogCompression() {
    assertThat(GeoToolsRasterBackend.cogCompressionTypes()).contains("JPEG");
  }

  @Test
  void writesYcbcrJpegCogForRgb() throws Exception {
    Path fixtures = dir.resolve("fixtures");
    BenchmarkFixtures.main(new String[] {fixtures.toString(), "1024"});
    Path input = fixtures.resolve("rgb.tif");
    Path output = dir.resolve("rgb-cog.tif");
    try (var backend = new GeoToolsRasterBackend()) {
      var dataset = backend.describe(input.toString());
      var progress = new ArrayList<Integer>();
      backend.write(dataset, output, false, () -> false, jpeg(75), progress::add);

      assertThat(progress).isNotEmpty().isSorted().contains(100);
      CogWriteTest.Tiff tiff = CogWriteTest.Tiff.read(output);
      assertThat(tiff.ifds).hasSize(2);
      CogWriteTest.Tiff.Ifd main = tiff.ifds.get(0);
      assertThat(main.shortValue(259)).isEqualTo(7);
      assertThat(main.shortValue(262)).isEqualTo(6);
      assertThat(main.shortValue(277)).isEqualTo(3);
      assertThat(main.shortValue(258)).isEqualTo(8);
      assertThat(main.tileOffsets()).isSorted();
      CogWriteTest.Tiff.Ifd overview = tiff.ifds.get(1);
      assertThat(overview.shortValue(254)).isEqualTo(1);
      assertThat(overview.shortValue(262)).isEqualTo(6);
      assertThat(overview.has(34735)).isFalse();
      tiff.checkLeadersAndTrailers();

      try (var source = new GeoTiffSource(new RasterDatasetRef(input.toString()));
          ImageInputStream stream = ImageIO.createImageInputStream(output.toFile())) {
        ImageReader reader = ImageIO.getImageReaders(stream).next();
        reader.setInput(stream);
        assertThat(reader.getNumImages(true)).isEqualTo(2);
        var mainDirectory =
            it.geosolutions.imageio.plugins.tiff.TIFFDirectory.createFromMetadata(
                reader.getImageMetadata(0));
        assertThat(mainDirectory.getTIFFField(530).getAsInt(0)).isEqualTo(2);
        assertThat(mainDirectory.getTIFFField(530).getAsInt(1)).isEqualTo(2);
        assertThat(mainDirectory.getTIFFField(531).getAsInt(0)).isEqualTo(1);
        assertThat(mainDirectory.getTIFFField(532)).isNotNull();
        reader.dispose();
      }
      assertRgbPlausible(input, output);
    }
    assertThat(temporaryFiles()).isEmpty();
  }

  @Test
  void writesGrayscaleJpegCog() throws Exception {
    Path input = grayFixture(dir.resolve("gray.tif"));
    Path output = dir.resolve("gray-cog.tif");
    try (var backend = new GeoToolsRasterBackend()) {
      backend.write(
          backend.describe(input.toString()), output, false, () -> false, jpeg(80), ignored -> {});
    }
    CogWriteTest.Tiff tiff = CogWriteTest.Tiff.read(output);
    assertThat(tiff.ifds).hasSize(2);
    assertThat(tiff.ifds.get(0).shortValue(259)).isEqualTo(7);
    assertThat(tiff.ifds.get(0).shortValue(262)).isEqualTo(1);
    assertThat(tiff.ifds.get(0).shortValue(277)).isEqualTo(1);
    try (var stream = ImageIO.createImageInputStream(output.toFile())) {
      ImageReader reader = ImageIO.getImageReaders(stream).next();
      reader.setInput(stream);
      var directory =
          it.geosolutions.imageio.plugins.tiff.TIFFDirectory.createFromMetadata(
              reader.getImageMetadata(0));
      assertThat(directory.getTIFFField(530)).isNull();
      reader.dispose();
    }
    try (var source = new GeoTiffSource(new RasterDatasetRef(input.toString()));
        var written = new GeoTiffSource(new RasterDatasetRef(output.toString()))) {
      var expected = source.read(new RasterReadRequest(new Rectangle(0, 0, 64, 64), 0));
      var actual = written.read(new RasterReadRequest(new Rectangle(0, 0, 64, 64), 0));
      for (int blockY = 0; blockY < 64; blockY += 8)
        for (int blockX = 0; blockX < 64; blockX += 8) {
          double expectedMean = 0, actualMean = 0;
          for (int y = blockY; y < blockY + 8; y++)
            for (int x = blockX; x < blockX + 8; x++) {
              expectedMean += expected.getSampleDouble(x, y, 0);
              actualMean += actual.getSampleDouble(x, y, 0);
            }
          assertThat(Math.abs(actualMean - expectedMean) / 64.0)
              .as("block %d,%d", blockX, blockY)
              .isLessThan(8.0);
        }
    }
    assertThat(temporaryFiles()).isEmpty();
  }

  @Test
  void qualityChangesTheFileSize() throws Exception {
    Path fixtures = dir.resolve("fixtures");
    BenchmarkFixtures.main(new String[] {fixtures.toString(), "1024"});
    Path input = fixtures.resolve("rgb.tif");
    Path low = dir.resolve("low.tif");
    Path high = dir.resolve("high.tif");
    try (var backend = new GeoToolsRasterBackend()) {
      var dataset = backend.describe(input.toString());
      backend.write(dataset, low, false, () -> false, jpeg(20), ignored -> {});
      backend.write(dataset, high, false, () -> false, jpeg(95), ignored -> {});
    }
    assertThat(Files.size(low)).isLessThan(Files.size(high));
    assertThat(temporaryFiles()).isEmpty();
  }

  @Test
  void rejectsJpegForUnsupportedRasters() throws Exception {
    Path fixtures = dir.resolve("fixtures");
    BenchmarkFixtures.main(new String[] {fixtures.toString(), "1024", "variants"});
    try (var backend = new GeoToolsRasterBackend()) {
      for (String kind : List.of("rgba", "palette", "numeric4")) {
        var dataset = backend.describe(fixtures.resolve(kind + ".tif").toString());
        assertThatThrownBy(
                () ->
                    backend.write(
                        dataset,
                        dir.resolve(kind + "-cog.tif"),
                        false,
                        () -> false,
                        jpeg(75),
                        ignored -> {}))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("JPEG COG supports");
      }
      var grayscale = backend.describe(grayFixture(dir.resolve("gray.tif")).toString());
      assertThat(grayscale.result().bands()).isNotEmpty();
    }
    assertThat(temporaryFiles()).isEmpty();
  }

  @Test
  void cleansTemporaryFilesWhenStoppedDuringJpeg() throws Exception {
    Path fixtures = dir.resolve("fixtures");
    BenchmarkFixtures.main(new String[] {fixtures.toString(), "1024"});
    Path input = fixtures.resolve("rgb.tif");
    Path output = dir.resolve("stopped.tif");
    try (var backend = new GeoToolsRasterBackend()) {
      var dataset = backend.describe(input.toString());
      AtomicInteger calls = new AtomicInteger();
      assertThatThrownBy(
              () ->
                  backend.write(
                      dataset,
                      output,
                      false,
                      () -> calls.incrementAndGet() > 2,
                      jpeg(75),
                      ignored -> {}))
          .isInstanceOf(IOException.class);
      assertThat(Files.exists(output)).isFalse();
    }
    assertThat(temporaryFiles()).isEmpty();
  }

  @Test
  void remoteReaderReadsJpegCogWithRanges() throws Exception {
    Path fixtures = dir.resolve("fixtures");
    BenchmarkFixtures.main(new String[] {fixtures.toString(), "1024"});
    Path output = dir.resolve("remote-rgb.tif");
    try (var backend = new GeoToolsRasterBackend()) {
      backend.write(
          backend.describe(fixtures.resolve("rgb.tif").toString()),
          output,
          false,
          () -> false,
          jpeg(75),
          ignored -> {});
    }
    byte[] file = Files.readAllBytes(output);
    java.util.concurrent.atomic.AtomicLong bytes = new java.util.concurrent.atomic.AtomicLong();
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress("127.0.0.1", 0), 0);
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
      assertThat(source.bands()).isEqualTo(3);
      assertThat(source.colorInfo().kind()).isEqualTo(RasterColorInfo.Kind.RGB);
      var request = new RasterReadRequest(new Rectangle(0, 0, 128, 128), 0);
      assertThat(source.read(request).getSampleDouble(10, 10, 0)).isBetween(0.0, 255.0);
      assertThat(bytes.get()).isLessThan(file.length / 2L);
    } finally {
      server.stop(0);
    }
    assertThat(temporaryFiles()).isEmpty();
  }

  /**
   * Compares 8x8 block means against the lossless source. JPEG chroma subsampling and block
   * artifacts make single-pixel comparisons meaningless on sharp synthetic patterns, while means
   * still catch a channel swap or a wrong color space loudly.
   */
  private static void assertRgbPlausible(Path sourceFile, Path cogFile) throws Exception {
    try (var source = new GeoTiffSource(new RasterDatasetRef(sourceFile.toString()));
        var written = new GeoTiffSource(new RasterDatasetRef(cogFile.toString()))) {
      var window = new Rectangle(96, 96, 128, 128);
      for (int band = 0; band < 3; band++) {
        var expected = source.read(new RasterReadRequest(window, band));
        var actual = written.read(new RasterReadRequest(window, band));
        for (int blockY = 96; blockY < 224; blockY += 8)
          for (int blockX = 96; blockX < 224; blockX += 8) {
            double expectedMean = 0, actualMean = 0;
            for (int y = blockY; y < blockY + 8; y++)
              for (int x = blockX; x < blockX + 8; x++) {
                expectedMean += expected.getSampleDouble(x, y, 0);
                actualMean += actual.getSampleDouble(x, y, 0);
              }
            assertThat(Math.abs(actualMean - expectedMean) / 64.0)
                .as("band %d block %d,%d", band, blockX, blockY)
                .isLessThan(12.0);
          }
      }
    }
  }

  private static Path grayFixture(Path file) throws Exception {
    int size = 1024;
    var image = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
    for (int y = 0; y < size; y++)
      for (int x = 0; x < size; x++) image.getRaster().setSample(x, y, 0, (x / 2 + y / 2) % 256);
    var coverage =
        new GridCoverageFactory()
            .create(
                "gray",
                image,
                new ReferencedEnvelope(
                    2600000, 2600000 + size, 1200000, 1200000 + size, CRS.decode("EPSG:2056", true)));
    var params = new org.geotools.gce.geotiff.GeoTiffWriteParams();
    params.setTilingMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
    params.setTiling(512, 512);
    params.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
    params.setCompressionType("Deflate");
    var options =
        org.geotools.coverage.grid.io.AbstractGridFormat.GEOTOOLS_WRITE_PARAMS.createValue();
    options.setValue(params);
    var writer = new org.geotools.gce.geotiff.GeoTiffWriter(file.toFile());
    try {
      writer.write(coverage, options);
    } finally {
      writer.dispose();
      coverage.dispose(true);
    }
    return file;
  }

  private List<Path> temporaryFiles() throws IOException {
    try (var paths = Files.list(dir)) {
      return paths.filter(p -> p.getFileName().toString().startsWith(".hop-raster-")).toList();
    }
  }
}
