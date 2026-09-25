package ch.so.agi.hop.raster.geotools;

import static org.assertj.core.api.Assertions.*;

import ch.so.agi.hop.raster.RasterOperation;
import ch.so.agi.hop.raster.RasterWriteOptions;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OverviewWriteTest {
  @TempDir Path dir;

  @Test
  void geotiffOverviewsKeepColorMetadataAcrossMultipleLevels() throws Exception {
    Path fixtures = dir.resolve("fixtures");
    BenchmarkFixtures.main(new String[] {fixtures.toString(), "1024", "variants"});
    for (String kind : new String[] {"rgba", "palette", "numeric4"}) {
      Path input = fixtures.resolve(kind + ".tif");
      Path output = dir.resolve(kind + ".tif");
      try (var backend = new GeoToolsRasterBackend()) {
        backend.write(
            backend.describe(input.toString()),
            output,
            false,
            () -> false,
            new RasterWriteOptions(
                RasterWriteOptions.Format.GEOTIFF,
                RasterWriteOptions.Overviews.AUTO,
                RasterWriteOptions.Resampling.AVERAGE,
                256,
                "Deflate",
                75),
            ignored -> {});
      }
      try (var source = new GeoTiffSource(new RasterDatasetRef(input.toString()));
          var written = new GeoTiffSource(new RasterDatasetRef(output.toString()));
          var stream = ImageIO.createImageInputStream(output.toFile())) {
        assertThat(written.colorInfo().kind()).isEqualTo(source.colorInfo().kind());
        assertThat(written.colorInfo().alphaBand()).isEqualTo(source.colorInfo().alphaBand());
        var reader = ImageIO.getImageReaders(stream).next();
        try {
          reader.setInput(stream);
          assertThat(reader.getNumImages(true)).isEqualTo(3);
          for (int level = 1; level <= 2; level++) {
            var pixels = reader.read(level).getRaster();
            assertThat(pixels.getWidth()).isEqualTo(1024 >> level);
            assertThat(pixels.getNumBands()).isEqualTo(source.bands());
            if (kind.equals("palette")) {
              var tags =
                  it.geosolutions.imageio.plugins.tiff.TIFFDirectory.createFromMetadata(
                      reader.getImageMetadata(level));
              assertThat(tags.getTIFFField(320).getAsChars())
                  .containsExactly(source.colorInfo().colorMap());
              int factor = 1 << level;
              var request = new RasterReadRequest(new Rectangle(3 * factor, 4 * factor, 1, 1), 0);
              assertThat(pixels.getSampleDouble(3, 4, 0))
                  .isEqualTo(source.read(request).getSampleDouble(3 * factor, 4 * factor, 0));
            }
          }
        } finally {
          reader.dispose();
        }
      }
    }
  }

  @Test
  void geotiffBigTiffSequenceIsReadable() throws Exception {
    Path input = RasterCoreTest.fixture(dir.resolve("input.tif"));
    Path output = dir.resolve("big.tif");
    var reader = new org.geotools.gce.geotiff.GeoTiffReader(input.toFile());
    var coverage = reader.read();
    try (var source = new GeoTiffSource(new RasterDatasetRef(input.toString()))) {
      var params = new org.geotools.gce.geotiff.GeoTiffWriteParams();
      params.setForceToBigTIFF(true);
      params.setTilingMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
      params.setTiling(512, 512);
      params.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
      params.setCompressionType("Deflate");
      GeoTiffOverviews.write(
          source,
          new RasterWriteOptions(
              RasterWriteOptions.Format.GEOTIFF,
              RasterWriteOptions.Overviews.AUTO,
              RasterWriteOptions.Resampling.AVERAGE,
              512,
              "Deflate",
              75),
          () -> false,
          output,
          coverage.getRenderedImage(),
          source.crs(),
          (AffineTransform) source.gridToWorld(),
          new double[] {1},
          new double[] {0},
          source.noData(0),
          source.colorInfo(),
          params,
          ignored -> {});
    } finally {
      coverage.dispose(true);
      reader.dispose();
    }
    byte[] header = Files.readAllBytes(output);
    assertThat((header[2] & 255) == 43 || (header[3] & 255) == 43).isTrue();
    try (var stream = ImageIO.createImageInputStream(output.toFile())) {
      var imageReader = ImageIO.getImageReaders(stream).next();
      try {
        imageReader.setInput(stream);
        assertThat(imageReader.getNumImages(true)).isEqualTo(2);
        assertThat(imageReader.read(1).getWidth()).isEqualTo(512);
      } finally {
        imageReader.dispose();
      }
    }
  }

  @Test
  void geotiffCancellationPreservesExistingTargetAndCleansStores() throws Exception {
    Path input = RasterCoreTest.fixture(dir.resolve("input.tif"));
    for (int stopAt : new int[] {1, 50}) {
      Path output = dir.resolve("existing-" + stopAt + ".tif");
      Files.writeString(output, "existing file");
      var stopped = new java.util.concurrent.atomic.AtomicBoolean();
      try (var backend = new GeoToolsRasterBackend()) {
        var dataset = backend.describe(input.toString());
        assertThatThrownBy(
                () ->
                    backend.write(
                        dataset,
                        output,
                        true,
                        stopped::get,
                        new RasterWriteOptions(
                            RasterWriteOptions.Format.GEOTIFF,
                            RasterWriteOptions.Overviews.AUTO,
                            RasterWriteOptions.Resampling.AVERAGE,
                            512,
                            "Deflate",
                            75),
                        value -> {
                          if (value >= stopAt) stopped.set(true);
                        }))
            .isInstanceOf(java.io.IOException.class);
      }
      assertThat(Files.readString(output)).isEqualTo("existing file");
      try (var files = Files.list(dir)) {
        assertThat(
                files.filter(p -> p.getFileName().toString().startsWith(".hop-raster-")).toList())
            .isEmpty();
      }
    }
  }

  @Test
  void clippedOverviewsUseLocalCoordinatesAndPreserveGeoReference() throws Exception {
    Path input = RasterCoreTest.fixture(dir.resolve("input.tif"));
    for (var format : RasterWriteOptions.Format.values()) {
      for (var resampling : RasterWriteOptions.Resampling.values()) {
        for (boolean shifted : new boolean[] {false, true}) {
          Path output = dir.resolve(format + "-" + resampling + "-" + shifted + ".tif");
          int x = shifted ? 7 : 0, y = shifted ? 9 : 0;
          var clip =
              new RasterOperation.Clip(
                  "clip",
                  RasterCoreTest.box(
                          2600000 + x * .25,
                          1200256 - (y + 665) * .25,
                          2600000 + (x + 777) * .25,
                          1200256 - y * .25)
                      .toText(),
                  false,
                  List.of(0),
                  null);
          try (var backend = new GeoToolsRasterBackend();
              var source = new GeoTiffSource(new RasterDatasetRef(input.toString()))) {
            var derived = new DerivedSource(source, clip, () -> false);
            assertThat(derived.bounds()).isEqualTo(new Rectangle(x, y, 777, 665));
            var progress = new ArrayList<Integer>();
            backend.write(
                backend.derive(backend.describe(input.toString()), clip),
                output,
                false,
                () -> false,
                new RasterWriteOptions(
                    format, RasterWriteOptions.Overviews.AUTO, resampling, 512, "Deflate", 75),
                progress::add);
            assertThat(progress).isSorted().contains(100);
            try (var stream = ImageIO.createImageInputStream(output.toFile());
                var result = new GeoTiffSource(new RasterDatasetRef(output.toString()))) {
              var reader = ImageIO.getImageReaders(stream).next();
              try {
                reader.setInput(stream);
                assertThat(reader.getNumImages(true)).isEqualTo(2);
                var main = reader.read(0).getRaster();
                var overview = reader.read(1).getRaster();
                assertThat(main.getWidth()).isEqualTo(777);
                assertThat(main.getHeight()).isEqualTo(665);
                assertThat(overview.getWidth()).isEqualTo(389);
                assertThat(overview.getHeight()).isEqualTo(333);
                for (int[] point : new int[][] {{0, 0}, {2, 0}, {255, 255}, {388, 332}}) {
                  int ox = point[0], oy = point[1];
                  int sx = 2 * ox, sy = 2 * oy;
                  var window =
                      new Rectangle(x + sx, y + sy, Math.min(2, 777 - sx), Math.min(2, 665 - sy));
                  var pixels = derived.read(new RasterReadRequest(window, 0));
                  double expected = pixels.getSampleDouble(window.x, window.y, 0);
                  assertThat(main.getSampleDouble(sx, sy, 0)).isEqualTo(expected);
                  if (resampling == RasterWriteOptions.Resampling.AVERAGE) {
                    double sum = 0;
                    int count = 0;
                    for (int yy = 0; yy < window.height; yy++)
                      for (int xx = 0; xx < window.width; xx++) {
                        double v = pixels.getSampleDouble(window.x + xx, window.y + yy, 0);
                        if (derived.valid(v, 0)) {
                          sum += v;
                          count++;
                        }
                      }
                    expected = count == 0 ? derived.noData(0) : (double) (float) (sum / count);
                  }
                  assertThat(overview.getSampleDouble(ox, oy, 0)).isEqualTo(expected);
                }
                var expectedWorld =
                    ((AffineTransform) derived.gridToWorld())
                        .transform(new Point2D.Double(x, y), null);
                var actualWorld =
                    ((AffineTransform) result.gridToWorld())
                        .transform(new Point2D.Double(0, 0), null);
                assertThat(actualWorld.getX()).isCloseTo(expectedWorld.getX(), within(1e-8));
                assertThat(actualWorld.getY()).isCloseTo(expectedWorld.getY(), within(1e-8));
                assertThat(result.noData(0)).isEqualTo(derived.noData(0));
                var tags =
                    it.geosolutions.imageio.plugins.tiff.TIFFDirectory.createFromMetadata(
                        reader.getImageMetadata(1));
                assertThat(tags.getTIFFField(254).getAsInt(0)).isEqualTo(1);
              } finally {
                reader.dispose();
              }
            }
          }
        }
      }
    }
    try (var files = Files.list(dir)) {
      assertThat(files.filter(p -> p.getFileName().toString().startsWith(".hop-raster-")).toList())
          .isEmpty();
    }
  }

  @Test
  void geoTiffNoneDoesNotCreateOverviews() throws Exception {
    Path input = RasterCoreTest.fixture(dir.resolve("input.tif"));
    Path output = dir.resolve("none.tif");
    try (var backend = new GeoToolsRasterBackend()) {
      backend.write(
          backend.describe(input.toString()),
          output,
          false,
          () -> false,
          RasterWriteOptions.geoTiff("Deflate"),
          ignored -> {});
    }
    try (var stream = ImageIO.createImageInputStream(output.toFile())) {
      var reader = ImageIO.getImageReaders(stream).next();
      try {
        reader.setInput(stream);
        assertThat(reader.getNumImages(true)).isEqualTo(1);
      } finally {
        reader.dispose();
      }
    }
  }
}
