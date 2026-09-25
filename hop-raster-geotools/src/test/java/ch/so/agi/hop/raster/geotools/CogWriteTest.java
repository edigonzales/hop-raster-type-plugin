package ch.so.agi.hop.raster.geotools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.so.agi.hop.raster.RasterWriteOptions;
import java.awt.Rectangle;
import java.awt.image.DataBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** COG output: layout, overviews, codecs and temporary file discipline. */
class CogWriteTest {
  @TempDir Path dir;

  @Test
  void writesCloudOptimizedLayoutAndRoundTripsMainPixels() throws Exception {
    Path input = RasterCoreTest.fixture(dir.resolve("input.tif"));
    Path output = dir.resolve("output.tif");
    try (var backend = new GeoToolsRasterBackend()) {
      var dataset = backend.describe(input.toString());
      var progress = new ArrayList<Integer>();
      backend.write(
          dataset,
          output,
          false,
          () -> false,
          RasterWriteOptions.cog("Deflate"),
          progress::add);

      assertThat(progress).isNotEmpty().isSorted().contains(100);
      Tiff tiff = Tiff.read(output);
      assertThat(tiff.ifds).hasSize(2);
      assertThat(tiff.ghost).isTrue();
      assertThat(tiff.ifds.get(0).offset % 2).isZero();
      assertThat(tiff.ifds.get(0).shortValue(256)).isEqualTo(1024);
      assertThat(tiff.ifds.get(0).shortValue(257)).isEqualTo(1024);
      assertThat(tiff.ifds.get(0).shortValue(259)).isEqualTo(32946);
      assertThat(tiff.ifds.get(0).shortValue(322)).isEqualTo(512);
      assertThat(tiff.ifds.get(0).shortValue(323)).isEqualTo(512);
      assertThat(tiff.ifds.get(0).has(34735)).isTrue();
      assertThat(tiff.ifds.get(0).has(42113)).isTrue();
      Tiff.Ifd overview = tiff.ifds.get(1);
      assertThat(overview.shortValue(254)).isEqualTo(1);
      assertThat(overview.shortValue(256)).isEqualTo(512);
      assertThat(overview.shortValue(257)).isEqualTo(512);
      assertThat(overview.has(34735)).isFalse();
      assertThat(overview.has(33550)).isFalse();
      assertThat(overview.has(33922)).isFalse();
      assertThat(overview.has(34264)).isFalse();
      assertThat(tiff.ifds.get(1).offset).isGreaterThan(tiff.ifds.get(0).offset);
      assertThat(tiff.minDataOffset(overview))
          .isGreaterThan(overview.offset)
          .isLessThan(tiff.minDataOffset(tiff.ifds.get(0)));
      assertThat(tiff.ifds.get(0).tileOffsets()).isSorted();
      assertThat(overview.tileOffsets()).isSorted();
      tiff.checkLeadersAndTrailers();

      try (var written = new GeoTiffSource(new RasterDatasetRef(output.toString()))) {
        try (var plain = new GeoTiffSource(new RasterDatasetRef(input.toString()))) {
          for (int y = 0; y < 1024; y += 256)
            for (int x = 0; x < 1024; x += 256) {
              var expected = plain.read(new RasterReadRequest(new Rectangle(x, y, 256, 256), 0));
              var actual = written.read(new RasterReadRequest(new Rectangle(x, y, 256, 256), 0));
              for (int row = 0; row < 256; row++)
                for (int column = 0; column < 256; column++)
                  assertThat(actual.getSampleDouble(x + column, y + row, 0))
                      .isEqualTo(expected.getSampleDouble(x + column, y + row, 0));
            }
        }
      }
    }
    assertThat(temporaryFiles()).isEmpty();
  }

  @Test
  void generatesNoDataAwareAverageOverviews() throws Exception {
    Path input = RasterCoreTest.fixture(dir.resolve("input.tif"));
    Path output = dir.resolve("output.tif");
    try (var backend = new GeoToolsRasterBackend()) {
      backend.write(
          backend.describe(input.toString()),
          output,
          false,
          () -> false,
          RasterWriteOptions.cog("Deflate"),
          ignored -> {});
    }
    try (var source = new GeoTiffSource(new RasterDatasetRef(input.toString()));
        ImageInputStream stream = ImageIO.createImageInputStream(output.toFile())) {
      ImageReader reader = ImageIO.getImageReaders(stream).next();
      reader.setInput(stream);
      assertThat(reader.getNumImages(true)).isEqualTo(2);
      var overview = reader.read(1).getRaster();
      assertThat(overview.getWidth()).isEqualTo(512);
      assertThat(overview.getHeight()).isEqualTo(512);
      // The NoData sample at (4, 0) is excluded; the overview pixel averages the valid samples.
      double[] values = new double[4];
      int[] xs = {4, 5, 4, 5}, ys = {0, 0, 1, 1};
      for (int i = 0; i < 4; i++)
        values[i] =
            source
                .read(new RasterReadRequest(new Rectangle(xs[i], ys[i], 1, 1), 0))
                .getSampleDouble(xs[i], ys[i], 0);
      double expected = (values[1] + values[2] + values[3]) / 3;
      assertThat(overview.getSampleDouble(2, 0, 0)).isEqualTo((double) (float) expected);
      reader.dispose();
    }
    assertThat(temporaryFiles()).isEmpty();
  }

  @Test
  void supportsEveryLosslessCodec() throws Exception {
    Path input = RasterCoreTest.fixture(dir.resolve("input.tif"));
    try (var backend = new GeoToolsRasterBackend()) {
      var dataset = backend.describe(input.toString());
      Map<String, Long> tags = new LinkedHashMap<>();
      tags.put("None", 1L);
      tags.put("Deflate", 32946L);
      tags.put("ZLib", 8L);
      tags.put("LZW", 5L);
      tags.put("ZSTD", 50000L);
      tags.put("PackBits", 32773L);
      for (var entry : tags.entrySet()) {
        Path output = dir.resolve(entry.getKey().toLowerCase() + ".tif");
        backend.write(
            dataset,
            output,
            false,
            () -> false,
            RasterWriteOptions.cog(entry.getKey()),
            ignored -> {});
        Tiff tiff = Tiff.read(output);
        assertThat(tiff.ifds.get(0).shortValue(259)).isEqualTo(entry.getValue());
        try (var written = new GeoTiffSource(new RasterDatasetRef(output.toString()))) {
          assertThat(
                  written
                      .read(new RasterReadRequest(new Rectangle(0, 0, 4, 3), 0))
                      .getSampleDouble(3, 2, 0))
              .isEqualTo(12);
        }
      }
    }
    assertThat(temporaryFiles()).isEmpty();
  }

  @Test
  void rejectsUnsupportedCogCompression() throws Exception {
    Path input = RasterCoreTest.fixture(dir.resolve("input.tif"));
    try (var backend = new GeoToolsRasterBackend()) {
      var dataset = backend.describe(input.toString());
      assertThatThrownBy(
              () ->
                  backend.write(
                      dataset,
                      dir.resolve("jpeg.tif"),
                      false,
                      () -> false,
                      RasterWriteOptions.cog("JPEG"),
                      ignored -> {}))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("COG output supports");
    }
    assertThat(temporaryFiles()).isEmpty();
  }

  @Test
  void cleansTemporaryFilesWhenStopped() throws Exception {
    Path input = RasterCoreTest.fixture(dir.resolve("input.tif"));
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
                      () -> calls.incrementAndGet() > 3,
                      RasterWriteOptions.cog("Deflate"),
                      ignored -> {}))
          .isInstanceOf(IOException.class);
      assertThat(Files.exists(output)).isFalse();
    }
    assertThat(temporaryFiles()).isEmpty();
  }

  @Test
  void writesBigTiffContainerDirectly() throws Exception {
    Path input = RasterCoreTest.fixture(dir.resolve("input.tif"));
    Path output = dir.resolve("big.tif");
    try (var source = new GeoTiffSource(new RasterDatasetRef(input.toString()))) {
      CogOutput.write(
          output,
          source,
          RasterWriteOptions.cog("Deflate"),
          true,
          () -> false,
          ignored -> {});
    }
    Tiff tiff = Tiff.read(output);
    assertThat(tiff.bigTiff).isTrue();
    assertThat(tiff.ifds).hasSize(2);
    assertThat(tiff.ifds.get(0).shortValue(256)).isEqualTo(1024);
    assertThat(tiff.ifds.get(1).shortValue(254)).isEqualTo(1);
    assertThat(tiff.minDataOffset(tiff.ifds.get(1)))
        .isGreaterThan(tiff.ifds.get(1).offset)
        .isLessThan(tiff.minDataOffset(tiff.ifds.get(0)));
    tiff.checkLeadersAndTrailers();
    try (var written = new GeoTiffSource(new RasterDatasetRef(output.toString()))) {
      assertThat(
              written
                  .read(new RasterReadRequest(new Rectangle(0, 0, 4, 3), 0))
                  .getSampleDouble(3, 2, 0))
          .isEqualTo(12);
    }
    assertThat(temporaryFiles()).isEmpty();
  }

  @Test
  void keepsGdalGhostAreaAndExactFirstIfdOffset() throws Exception {
    Path input = RasterCoreTest.fixture(dir.resolve("input.tif"));
    Path output = dir.resolve("output.tif");
    try (var backend = new GeoToolsRasterBackend()) {
      backend.write(
          backend.describe(input.toString()),
          output,
          false,
          () -> false,
          RasterWriteOptions.cog("Deflate"),
          ignored -> {});
    }
    Tiff tiff = Tiff.read(output);
    assertThat(tiff.ghost).isTrue();
    assertThat(tiff.ifds.get(0).offset).isEqualTo(tiff.expectedFirstIfdOffset());
    assertThat(tiff.ifds.get(0).offset).isEqualTo(192);
  }

  @Test
  void supportsColorOrientations() throws Exception {
    Path fixtures = dir.resolve("fixtures");
    BenchmarkFixtures.main(new String[] {fixtures.toString(), "1024", "variants"});
    try (var backend = new GeoToolsRasterBackend()) {
      for (String kind : new String[] {"rgba", "palette", "numeric4"}) {
        Path input = fixtures.resolve(kind + ".tif");
        Path output = dir.resolve(kind + "-cog.tif");
        backend.write(
            backend.describe(input.toString()),
            output,
            false,
            () -> false,
            RasterWriteOptions.cog("Deflate"),
            ignored -> {});
        Tiff tiff = Tiff.read(output);
        assertThat(tiff.ifds).hasSize(2);
        Tiff.Ifd main = tiff.ifds.get(0);
        if (kind.equals("rgba")) {
          assertThat(main.shortValue(262)).isEqualTo(2);
          assertThat(main.has(338)).isTrue();
        } else if (kind.equals("palette")) {
          assertThat(main.shortValue(262)).isEqualTo(3);
          assertThat(main.has(320)).isTrue();
          // Averaging palette indexes is not meaningful; the overview keeps source samples.
          try (var source = new GeoTiffSource(new RasterDatasetRef(input.toString()));
              ImageInputStream stream = ImageIO.createImageInputStream(output.toFile())) {
            ImageReader reader = ImageIO.getImageReaders(stream).next();
            reader.setInput(stream);
            var overview = reader.read(1).getRaster();
            var sample =
                source
                    .read(new RasterReadRequest(new Rectangle(6, 8, 1, 1), 0))
                    .getSampleDouble(6, 8, 0);
            assertThat(overview.getSampleDouble(3, 4, 0)).isEqualTo(sample);
            reader.dispose();
          }
        }
        assertSamePixels(input, output, kind);
      }
    }
    assertThat(temporaryFiles()).isEmpty();
  }

  @Test
  void ordersMultipleOverviewLevelsSmallestFirst() throws Exception {
    Path fixtures = dir.resolve("fixtures");
    BenchmarkFixtures.main(new String[] {fixtures.toString(), "2048"});
    Path input = fixtures.resolve("dem.tif");
    Path output = dir.resolve("dem-cog.tif");
    try (var backend = new GeoToolsRasterBackend()) {
      backend.write(
          backend.describe(input.toString()),
          output,
          false,
          () -> false,
          RasterWriteOptions.cog("Deflate"),
          ignored -> {});
    }
    Tiff tiff = Tiff.read(output);
    assertThat(tiff.ifds).hasSize(3);
    assertThat(tiff.ifds.get(1).shortValue(256)).isEqualTo(1024);
    assertThat(tiff.ifds.get(2).shortValue(256)).isEqualTo(512);
    assertThat(tiff.minDataOffset(tiff.ifds.get(2)))
        .isLessThan(tiff.minDataOffset(tiff.ifds.get(1)));
    assertThat(tiff.minDataOffset(tiff.ifds.get(1)))
        .isLessThan(tiff.minDataOffset(tiff.ifds.get(0)));
    tiff.checkLeadersAndTrailers();
    assertThat(temporaryFiles()).isEmpty();
  }

  @Test
  void overviewsCanBeDisabled() throws Exception {
    Path input = RasterCoreTest.fixture(dir.resolve("input.tif"));
    Path output = dir.resolve("no-overview.tif");
    var options =
        new RasterWriteOptions(
            RasterWriteOptions.Format.COG,
            RasterWriteOptions.Overviews.NONE,
            RasterWriteOptions.Resampling.AVERAGE,
            512,
            "Deflate");
    try (var backend = new GeoToolsRasterBackend()) {
      backend.write(backend.describe(input.toString()), output, false, () -> false, options, ignored -> {});
    }
    Tiff tiff = Tiff.read(output);
    assertThat(tiff.ifds).hasSize(1);
    assertThat(tiff.ifds.get(0).has(34735)).isTrue();
    assertThat(temporaryFiles()).isEmpty();
  }

  @Test
  void nearestResamplingKeepsSourceSamples() throws Exception {
    Path input = RasterCoreTest.fixture(dir.resolve("input.tif"));
    Path output = dir.resolve("nearest.tif");
    var options =
        new RasterWriteOptions(
            RasterWriteOptions.Format.COG,
            RasterWriteOptions.Overviews.AUTO,
            RasterWriteOptions.Resampling.NEAREST,
            512,
            "Deflate");
    try (var backend = new GeoToolsRasterBackend()) {
      backend.write(backend.describe(input.toString()), output, false, () -> false, options, ignored -> {});
    }
    try (var source = new GeoTiffSource(new RasterDatasetRef(input.toString()));
        ImageInputStream stream = ImageIO.createImageInputStream(output.toFile())) {
      ImageReader reader = ImageIO.getImageReaders(stream).next();
      reader.setInput(stream);
      var overview = reader.read(1).getRaster();
      double expected =
          source
              .read(new RasterReadRequest(new Rectangle(6, 8, 1, 1), 0))
              .getSampleDouble(6, 8, 0);
      assertThat(overview.getSampleDouble(3, 4, 0)).isEqualTo(expected);
      reader.dispose();
    }
    assertThat(temporaryFiles()).isEmpty();
  }

  @Test
  void cleansTemporaryFilesWhenSourceFails() throws Exception {
    Path input = RasterCoreTest.fixture(dir.resolve("input.tif"));
    Path output = dir.resolve("failed.tif");
    try (var backend = new GeoToolsRasterBackend()) {
      var dataset = backend.describe(input.toString());
      GeoTiffSource raw = (GeoTiffSource) AcceptanceProbe.field(backend, "source");
      AtomicInteger calls = new AtomicInteger();
      assertThatThrownBy(
              () ->
                  backend.write(
                      dataset,
                      output,
                      false,
                      () -> {
                        if (calls.incrementAndGet() == 3) raw.close();
                        return false;
                      },
                      RasterWriteOptions.cog("Deflate"),
                      ignored -> {}))
          .isInstanceOf(IOException.class);
      assertThat(Files.exists(output)).isFalse();
    }
    assertThat(temporaryFiles()).isEmpty();
  }

  @Test
  void remoteReaderReadsGeneratedCogWithRanges() throws Exception {
    Path input = RasterCoreTest.fixture(dir.resolve("input.tif"));
    Path output = dir.resolve("remote.tif");
    try (var backend = new GeoToolsRasterBackend()) {
      backend.write(
          backend.describe(input.toString()),
          output,
          false,
          () -> false,
          RasterWriteOptions.cog("Deflate"),
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
      var request = new RasterReadRequest(new Rectangle(0, 0, 256, 256), 0);
      assertThat(source.read(request).getSampleDouble(3, 2, 0)).isEqualTo(12);
      long first = bytes.get();
      source.read(request);
      assertThat(bytes.get()).isEqualTo(first);
      assertThat(first).isLessThan(file.length / 2L);
    } finally {
      server.stop(0);
    }
    assertThat(temporaryFiles()).isEmpty();
  }

  private static void assertSamePixels(Path expectedFile, Path actualFile, String kind)
      throws Exception {
    try (var expected = new GeoTiffSource(new RasterDatasetRef(expectedFile.toString()));
        var actual = new GeoTiffSource(new RasterDatasetRef(actualFile.toString()))) {
      int width = expected.bounds().width, height = expected.bounds().height;
      int bands = expected.bands();
      for (int y = 0; y < height; y += 256)
        for (int x = 0; x < width; x += 256) {
          int w = Math.min(256, width - x), h = Math.min(256, height - y);
          for (int band = 0; band < bands; band++) {
            double[] a =
                expected
                    .read(new RasterReadRequest(new Rectangle(x, y, w, h), band))
                    .getSamples(x, y, w, h, 0, (double[]) null);
            double[] b =
                actual
                    .read(new RasterReadRequest(new Rectangle(x, y, w, h), band))
                    .getSamples(x, y, w, h, 0, (double[]) null);
            for (int i = 0; i < a.length; i++)
              if (Double.compare(a[i], b[i]) != 0)
                throw new AssertionError(
                    kind
                        + " band "
                        + band
                        + " pixel "
                        + (x + i % w)
                        + ","
                        + (y + i / w)
                        + " expected "
                        + a[i]
                        + " but was "
                        + b[i]);
          }
        }
    }
  }

  private List<Path> temporaryFiles() throws IOException {
    try (var paths = Files.list(dir)) {
      return paths.filter(p -> p.getFileName().toString().startsWith(".hop-raster-")).toList();
    }
  }

  /** Minimal classic/BigTIFF reader for structural assertions. */
  static final class Tiff {
    final byte[] bytes;
    final boolean bigTiff;
    final ByteOrder order;
    final boolean ghost;
    final List<Ifd> ifds = new ArrayList<>();

    static final class Ifd {
      final long offset;
      final Map<Integer, long[]> values = new LinkedHashMap<>();

      Ifd(long offset) {
        this.offset = offset;
      }

      boolean has(int tag) {
        return values.containsKey(tag);
      }

      long shortValue(int tag) {
        return values.get(tag)[0];
      }

      long[] tileOffsets() {
        return values.get(324);
      }

      long[] tileByteCounts() {
        return values.get(325);
      }
    }

    private Tiff(byte[] bytes, boolean bigTiff, ByteOrder order, boolean ghost) {
      this.bytes = bytes;
      this.bigTiff = bigTiff;
      this.order = order;
      this.ghost = ghost;
    }

    static Tiff read(Path file) throws IOException {
      byte[] bytes = Files.readAllBytes(file);
      ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
      boolean bigTiff = bytes[2] == 43 && bytes[3] == 0;
      ByteOrder order = bytes[0] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
      buffer.order(order);
      long first = bigTiff ? buffer.getLong(8) : buffer.getInt(4) & 0xffffffffL;
      boolean ghost =
          new String(bytes, bigTiff ? 16 : 8, 30, java.nio.charset.StandardCharsets.ISO_8859_1)
              .startsWith("GDAL_STRUCTURAL_METADATA_SIZE=");
      Tiff tiff = new Tiff(bytes, bigTiff, order, ghost);
      long offset = first;
      while (offset != 0) {
        Ifd ifd = tiff.readIfd(buffer, offset);
        tiff.ifds.add(ifd);
        offset = ifd.values.containsKey(-1) ? ifd.values.get(-1)[0] : 0;
      }
      return tiff;
    }

    private Ifd readIfd(ByteBuffer buffer, long offset) {
      Ifd ifd = new Ifd(offset);
      buffer.position((int) offset);
      long entries = bigTiff ? buffer.getLong() : buffer.getShort() & 0xffffL;
      int entrySize = bigTiff ? 20 : 12;
      for (int i = 0; i < entries; i++) {
        int position = (int) offset + (bigTiff ? 8 : 2) + i * entrySize;
        buffer.position(position);
        int tag = buffer.getShort() & 0xffff;
        int type = buffer.getShort() & 0xffff;
        long count = bigTiff ? buffer.getLong() : buffer.getInt() & 0xffffffffL;
        long size = count * sizeOf(type);
        long valueOrOffset = bigTiff ? buffer.getLong() : buffer.getInt() & 0xffffffffL;
        if (size <= (bigTiff ? 8 : 4)) {
          ifd.values.put(tag, inline(buffer, position + (bigTiff ? 12 : 8), type, count));
        } else {
          ifd.values.put(tag, external(buffer, valueOrOffset, type, count));
        }
      }
      buffer.position((int) offset + (bigTiff ? 8 : 2) + (int) entries * entrySize);
      long next = bigTiff ? buffer.getLong() : buffer.getInt() & 0xffffffffL;
      ifd.values.put(-1, new long[] {next});
      return ifd;
    }

    private long[] inline(ByteBuffer buffer, int position, int type, long count) {
      buffer.position(position);
      return external(buffer, position, type, count);
    }

    private long[] external(ByteBuffer buffer, long offset, int type, long count) {
      int save = buffer.position();
      buffer.position((int) offset);
      long[] values = new long[type == 5 || type == 10 ? (int) count * 2 : (int) count];
      for (int i = 0; i < values.length; i++) {
        values[i] =
            switch (type) {
              case 1, 2, 6, 7 -> buffer.get() & 0xff;
              case 3, 8 -> buffer.getShort() & 0xffff;
              case 4, 5, 9, 10, 13 -> buffer.getInt() & 0xffffffffL;
              case 11 -> buffer.getInt() & 0xffffffffL;
              case 12, 16, 17, 18 -> buffer.getLong();
              default -> throw new IllegalArgumentException("Unsupported type " + type);
            };
      }
      buffer.position(save);
      return values;
    }

    private static int sizeOf(int type) {
      return switch (type) {
        case 1, 2, 6, 7 -> 1;
        case 3, 8 -> 2;
        case 4, 9, 11, 13 -> 4;
        case 5, 10, 12, 16, 17, 18 -> 8;
        default -> throw new IllegalArgumentException("Unsupported type " + type);
      };
    }

    long minDataOffset(Ifd ifd) {
      long min = Long.MAX_VALUE;
      for (long offset : ifd.tileOffsets()) if (offset > 0) min = Math.min(min, offset);
      return min;
    }

    /** The offset GDAL's validator derives from the ghost area header. */
    long expectedFirstIfdOffset() {
      int header = bigTiff ? 16 : 8;
      if (!ghost) return header;
      String prefix =
          new String(bytes, header, 43, java.nio.charset.StandardCharsets.ISO_8859_1);
      int size = Integer.parseInt(prefix.substring(30, 36));
      long expected = header + 43 + size;
      return expected + expected % 2;
    }

    void checkLeadersAndTrailers() {
      ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
      for (Ifd ifd : ifds) {
        long[] offsets = ifd.tileOffsets();
        long[] counts = ifd.tileByteCounts();
        for (int tile = 0; tile < offsets.length; tile++) {
          long offset = offsets[tile];
          int count = (int) counts[tile];
          assertThat(buffer.getInt((int) offset - 4)).isEqualTo(count);
          assertThat(buffer.getInt((int) offset + count - 4))
              .isEqualTo(buffer.getInt((int) offset + count));
        }
      }
    }
  }
}
