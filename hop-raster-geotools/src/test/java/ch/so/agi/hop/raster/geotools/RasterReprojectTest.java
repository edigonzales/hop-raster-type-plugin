package ch.so.agi.hop.raster.geotools;

import static org.assertj.core.api.Assertions.*;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.referencing.CRS;
import org.geotools.referencing.operation.transform.AffineTransform2D;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RasterReprojectTest {
  @TempDir Path dir;

  static class Source implements RasterSource {
    int width = 2, height = 2, type = DataBuffer.TYPE_DOUBLE;
    double[][] values = {{0, 10, 20, 30}};
    Double nd;
    RasterColorInfo color = RasterColorInfo.numeric();
    CoordinateReferenceSystem crs;
    MathTransform transform = new AffineTransform2D(1, 0, 0, -1, .5, 1.5);
    long maxPixels;
    int reads;

    Source() throws Exception {
      crs = CRS.decode("EPSG:3857", true);
    }

    public Rectangle bounds() {
      return new Rectangle(width, height);
    }

    public CoordinateReferenceSystem crs() {
      return crs;
    }

    public MathTransform gridToWorld() {
      return transform;
    }

    public int bands() {
      return values.length;
    }

    public int dataType() {
      return type;
    }

    public RasterColorInfo colorInfo() {
      return color;
    }

    public Double noData(int band) {
      return nd;
    }

    public boolean valid(double value, int band) {
      return Double.isFinite(value) && (nd == null || value != nd);
    }

    public double physical(double value, int band) {
      return value * scale(band) + offset(band);
    }

    public double scale(int band) {
      return color.kind() == RasterColorInfo.Kind.NUMERIC ? band + 1 : 1;
    }

    public double offset(int band) {
      return color.kind() == RasterColorInfo.Kind.NUMERIC ? band * 100 : 0;
    }

    public Raster read(RasterReadRequest request) {
      Rectangle r = request.window();
      assertThat(bounds().contains(r)).isTrue();
      maxPixels = Math.max(maxPixels, (long) r.width * r.height);
      reads++;
      var model = new BandedSampleModel(DataBuffer.TYPE_DOUBLE, r.width, r.height, 1);
      var data = Raster.createWritableRaster(model, model.createDataBuffer(), new Point(r.x, r.y));
      for (int y = r.y; y < r.y + r.height; y++)
        for (int x = r.x; x < r.x + r.width; x++)
          data.setSample(
              x,
              y,
              0,
              values[request.band()].length == 1
                  ? values[request.band()][0]
                  : values[request.band()][y * width + x]);
      return data;
    }

    public void close() {}
  }

  static RasterReprojectRequest request(Path file, double resolution, boolean bilinear) {
    return new RasterReprojectRequest(
        "",
        resolution,
        resolution,
        null,
        bilinear
            ? RasterReprojectRequest.Interpolation.BILINEAR
            : RasterReprojectRequest.Interpolation.NEAREST,
        RasterReprojectRequest.OutputType.AUTO,
        null,
        null,
        file,
        false);
  }

  static double[] band(GeoTiffSource source, int band) throws Exception {
    var r = source.read(new RasterReadRequest(source.bounds(), band));
    return r.getSamples(r.getMinX(), r.getMinY(), r.getWidth(), r.getHeight(), 0, (double[]) null);
  }

  GeoTiffSource write(Source source, RasterReprojectRequest request) throws Exception {
    RasterReproject.write(source, request, () -> false);
    return new GeoTiffSource(new RasterDatasetRef(request.output().toString()));
  }

  @Test
  void nearestIdentityPreservesAllBandsGridAndMetadata() throws Exception {
    var source = new Source();
    source.values = new double[][] {{0, 10, 20, 30}, {11, 12, 13, 14}, {100, 200, 300, 400}};
    try (var result = write(source, request(dir.resolve("identity.tif"), 1, false))) {
      assertThat(result.bands()).isEqualTo(3);
      assertThat(result.colorInfo().kind()).isEqualTo(RasterColorInfo.Kind.NUMERIC);
      for (int b = 0; b < 3; b++) {
        assertThat(band(result, b)).containsExactly(source.values[b]);
        assertThat(result.scale(b)).isEqualTo(b + 1);
        assertThat(result.offset(b)).isEqualTo(b * 100);
      }
      assertThat(result.gridToWorld()).isEqualTo(source.gridToWorld());
    }
  }

  @Test
  void bilinearRenormalizesMissingNeighboursAndKeepsZero() throws Exception {
    var source = new Source();
    source.values[0] = new double[] {0, Double.NaN, 20, 30};
    try (var result = write(source, request(dir.resolve("weighted.tif"), 2, true))) {
      assertThat(result.dataType()).isEqualTo(DataBuffer.TYPE_DOUBLE);
      assertThat(band(result, 0)[0]).isCloseTo(50d / 3, within(1e-8));
    }
    source.values[0] = new double[] {0, 10, 20, 30};
    try (var result = write(source, request(dir.resolve("mean.tif"), 2, true))) {
      assertThat(band(result, 0)).containsExactly(15);
    }
  }

  @Test
  void sourceOverrideReplacesSentinelAndAllMissingWritesNoData() throws Exception {
    var source = new Source();
    source.nd = 10d;
    var req =
        new RasterReprojectRequest(
            "",
            2,
            2,
            null,
            RasterReprojectRequest.Interpolation.BILINEAR,
            RasterReprojectRequest.OutputType.AUTO,
            20d,
            -9999d,
            dir.resolve("override.tif"),
            false);
    try (var result = write(source, req)) {
      assertThat(band(result, 0)[0]).isCloseTo(40d / 3, within(1e-8));
    }
    source.values[0] = new double[] {Double.NaN};
    try (var result = write(source, request(dir.resolve("empty.tif"), 1, false))) {
      assertThat(band(result, 0)).containsExactly(10, 10, 10, 10);
    }
  }

  @Test
  void upsamplingHasNoHalfPixelShiftOrInvalidEdgeValues() throws Exception {
    var source = new Source();
    try (var result = write(source, request(dir.resolve("fine.tif"), .5, false))) {
      assertThat(band(result, 0))
          .containsExactly(0, 0, 10, 10, 0, 0, 10, 10, 20, 20, 30, 30, 20, 20, 30, 30);
    }
    try (var result = write(source, request(dir.resolve("fine-linear.tif"), .5, true))) {
      assertThat(band(result, 0))
          .containsExactly(
              0, 2.5, 7.5, 10, 5, 7.5, 12.5, 15, 15, 17.5, 22.5, 25, 20, 22.5, 27.5, 30);
    }
  }

  @Test
  void explicitExtentExpandsToGridAndFillsOutsideSource() throws Exception {
    var source = new Source();
    var req =
        new RasterReprojectRequest(
            "",
            1,
            1,
            new RasterReprojectRequest.Extent(-.2, 0, 1.7, 2),
            RasterReprojectRequest.Interpolation.NEAREST,
            RasterReprojectRequest.OutputType.AUTO,
            null,
            null,
            dir.resolve("bbox.tif"),
            false);
    var grid = RasterTargetGrid.create(source, req);
    assertThat(grid.width()).isEqualTo(3);
    assertThat(grid.height()).isEqualTo(2);
    try (var result = write(source, req)) {
      assertThat(band(result, 0)).containsExactly(Double.NaN, 0, 10, Double.NaN, 20, 30);
    }
  }

  @Test
  void numericIntegerRoundingOverflowAndSentinelCollision() throws Exception {
    var source = new Source();
    source.type = DataBuffer.TYPE_BYTE;
    source.nd = 255d;
    source.values[0] = new double[] {0, 1, 1, 1};
    var req =
        new RasterReprojectRequest(
            "",
            2,
            2,
            null,
            RasterReprojectRequest.Interpolation.BILINEAR,
            RasterReprojectRequest.OutputType.SOURCE,
            null,
            null,
            dir.resolve("integer.tif"),
            false);
    try (var result = write(source, req)) {
      assertThat(band(result, 0)).containsExactly(1);
    }
    var collision =
        new RasterReprojectRequest(
            "",
            1,
            1,
            null,
            RasterReprojectRequest.Interpolation.NEAREST,
            RasterReprojectRequest.OutputType.AUTO,
            null,
            1d,
            dir.resolve("collision.tif"),
            false);
    assertThatThrownBy(() -> write(source, collision)).hasStackTraceContaining("collides");
    assertThat(collision.output()).doesNotExist();
    assertThatThrownBy(() -> RasterSampleValues.convert(256, DataBuffer.TYPE_BYTE))
        .hasMessageContaining("overflow");
    assertThatThrownBy(() -> RasterSampleValues.convert(Double.MAX_VALUE, DataBuffer.TYPE_FLOAT))
        .hasMessageContaining("overflow");
    assertThatThrownBy(() -> RasterSampleValues.validateNoData(.1, DataBuffer.TYPE_FLOAT))
        .hasMessageContaining("representable");
  }

  @Test
  void paletteNearestKeepsUInt16TableAndBilinearExpandsToRgba() throws Exception {
    var source = new Source();
    source.type = DataBuffer.TYPE_BYTE;
    source.values[0] = new double[] {0, 1, 0, 1};
    source.color =
        new RasterColorInfo(
            RasterColorInfo.Kind.PALETTE, -1, false, new char[] {65535, 0, 0, 12345, 0, 65535});
    try (var result = write(source, request(dir.resolve("palette.tif"), 1, false))) {
      assertThat(result.colorInfo().kind()).isEqualTo(RasterColorInfo.Kind.PALETTE);
      assertThat(band(result, 0)).containsExactly(0, 1, 0, 1);
      char[] map = result.colorInfo().colorMap();
      assertThat((int) map[257]).isEqualTo(12345);
      try (var again =
          new GeoTiffSource(new RasterDatasetRef(dir.resolve("palette.tif").toString()))) {
        RasterReproject.write(
            again, request(dir.resolve("palette-again.tif"), 2, true), () -> false);
      }
    }
    try (var result = write(source, request(dir.resolve("palette-linear.tif"), 2, true))) {
      assertThat(result.bands()).isEqualTo(4);
      assertThat(result.dataType()).isEqualTo(DataBuffer.TYPE_USHORT);
      assertThat(band(result, 0)).containsExactly(32768);
      assertThat(band(result, 1)).containsExactly(6172);
      assertThat(band(result, 2)).containsExactly(32768);
      assertThat(band(result, 3)).containsExactly(65535);
    }
  }

  @Test
  void alphaWeightedColorsDoNotAcquireTransparentPixelColors() throws Exception {
    var source = new Source();
    source.type = DataBuffer.TYPE_BYTE;
    source.color = new RasterColorInfo(RasterColorInfo.Kind.RGB, 3, false, null);
    source.values =
        new double[][] {{255, 0, 255, 0}, {0, 0, 0, 0}, {0, 255, 0, 255}, {255, 0, 255, 0}};
    try (var result = write(source, request(dir.resolve("alpha.tif"), 2, true))) {
      assertThat(result.colorInfo().alphaBand()).isEqualTo(3);
      assertThat(band(result, 0)).containsExactly(255);
      assertThat(band(result, 1)).containsExactly(0);
      assertThat(band(result, 2)).containsExactly(0);
      assertThat(band(result, 3)).containsExactly(128);
    }
  }

  @Test
  void rgbAndGrayAlphaKeepTypesAndAssociatedAlphaIsUnpremultiplied() throws Exception {
    var source = new Source();
    source.type = DataBuffer.TYPE_BYTE;
    source.color = new RasterColorInfo(RasterColorInfo.Kind.RGB, -1, false, null);
    source.values = new double[][] {{10}, {20}, {30}};
    try (var result = write(source, request(dir.resolve("rgb.tif"), 1, false))) {
      assertThat(result.bands()).isEqualTo(4);
      assertThat(band(result, 0)).containsExactly(10, 10, 10, 10);
      assertThat(band(result, 1)).containsExactly(20, 20, 20, 20);
      assertThat(band(result, 2)).containsExactly(30, 30, 30, 30);
    }
    source.color = new RasterColorInfo(RasterColorInfo.Kind.GRAY_ALPHA, 1, true, null);
    source.values = new double[][] {{64}, {128}};
    try (var result = write(source, request(dir.resolve("gray.tif"), 1, false))) {
      assertThat(result.colorInfo().associatedAlpha()).isFalse();
      assertThat(band(result, 0)).containsExactly(128, 128, 128, 128);
      assertThat(band(result, 1)).containsExactly(128, 128, 128, 128);
    }
  }

  @Test
  void veryLargeSourceDownsamplingUsesSmallWindows() throws Exception {
    var source = new Source();
    source.width = 100000;
    source.height = 100000;
    source.transform = new AffineTransform2D(1, 0, 0, -1, .5, 99999.5);
    source.values[0] = new double[] {42};
    try (var result = write(source, request(dir.resolve("large.tif"), 25000, true))) {
      assertThat(band(result, 0)).hasSize(16).containsOnly(42);
      assertThat(source.maxPixels).isLessThanOrEqualTo(256 * 1024);
      assertThat(source.reads).isLessThanOrEqualTo(64);
    }
  }

  @Test
  void crsTransformationAndRotatedRectangularSource() throws Exception {
    var source = new Source();
    source.crs = CRS.decode("EPSG:2056", true);
    source.transform = new AffineTransform2D(1, 0, 0, -1, 2600000.5, 1200001.5);
    var req =
        new RasterReprojectRequest(
            "EPSG:4326",
            .00001,
            .00001,
            null,
            RasterReprojectRequest.Interpolation.NEAREST,
            RasterReprojectRequest.OutputType.AUTO,
            null,
            null,
            dir.resolve("wgs.tif"),
            false);
    var grid = RasterTargetGrid.create(source, req);
    double[] p = {0, 0};
    grid.gridToWorld().transform(p, 0, p, 0, 1);
    assertThat(p[0]).isCloseTo(7.43863, within(.0001));
    assertThat(p[1]).isCloseTo(46.95108, within(.0001));
    try (var result = write(source, req)) {
      assertThat(CRS.lookupEpsgCode(result.crs(), true)).isEqualTo(4326);
    }
    source.crs = CRS.decode("EPSG:3857", true);
    source.transform = new AffineTransform2D(0, 1, 2, 0, 1, .5);
    var rectangular =
        new RasterReprojectRequest(
            "",
            2,
            1,
            null,
            RasterReprojectRequest.Interpolation.NEAREST,
            RasterReprojectRequest.OutputType.AUTO,
            null,
            null,
            dir.resolve("rotated.tif"),
            false);
    try (var result = write(source, rectangular)) {
      assertThat(band(result, 0)).containsExactly(10, 30, 0, 20);
    }
  }

  @Test
  void outputPreservedOnCancellationFailureAndSameFileRejected() throws Exception {
    var source = new Source();
    Path file = dir.resolve("existing.tif");
    Files.writeString(file, "original");
    assertThatThrownBy(() -> write(source, request(file, 1, false))).hasMessageContaining("exists");
    var req =
        new RasterReprojectRequest(
            "",
            1,
            1,
            null,
            RasterReprojectRequest.Interpolation.NEAREST,
            RasterReprojectRequest.OutputType.AUTO,
            null,
            null,
            file,
            true);
    var checks = new AtomicInteger();
    assertThatThrownBy(() -> RasterReproject.write(source, req, () -> checks.incrementAndGet() > 2))
        .hasStackTraceContaining("stopped");
    assertThat(Files.readString(file)).isEqualTo("original");
    try (var files = Files.list(dir)) {
      assertThat(files.map(p -> p.getFileName().toString()).toList())
          .containsExactly("existing.tif");
    }
    Path input = dir.resolve("input.tif");
    try (var result = write(source, request(input, 1, false))) {
      assertThatThrownBy(() -> RasterReproject.write(result, request(input, 1, false), () -> false))
          .hasMessageContaining("differ");
    }
  }

  @Test
  void independentlyWrittenColorInputsPreserveRawChannelsAndAlpha() throws Exception {
    for (int alpha : new int[] {1, 2}) {
      Path file =
          RasterColorFixture.write(
              dir.resolve("input-alpha-" + alpha + ".tif"),
              2,
              alpha,
              new int[][] {{64, 0}, {0, 128}, {0, 0}, {128, 128}});
      try (var source = new GeoTiffSource(new RasterDatasetRef(file.toString()))) {
        assertThat(source.colorInfo().associatedAlpha()).isEqualTo(alpha == 1);
        assertThat(band(source, 0)).containsExactly(64, 0);
        Path output = dir.resolve("output-alpha-" + alpha + ".tif");
        RasterReproject.write(source, request(output, 1, false), () -> false);
        try (var result = new GeoTiffSource(new RasterDatasetRef(output.toString()))) {
          assertThat(band(result, 0)).containsExactly(alpha == 1 ? 128 : 64, 0);
          assertThat(band(result, 1)).containsExactly(0, alpha == 1 ? 255 : 128);
          assertThat(band(result, 3)).containsExactly(128, 128);
        }
      }
    }
  }

  @Test
  void multipleOutputTilesHaveNoSeams() throws Exception {
    var source = new Source();
    source.width = 520;
    source.height = 4;
    source.transform = new AffineTransform2D(1, 0, 0, -1, .5, 3.5);
    source.values[0] = new double[source.width * source.height];
    for (int y = 0; y < source.height; y++)
      for (int x = 0; x < source.width; x++) source.values[0][y * source.width + x] = x + 10 * y;
    try (var result = write(source, request(dir.resolve("tiles.tif"), .5, true))) {
      double[] values = band(result, 0);
      for (int y = 0; y < 8; y++)
        for (int x = 0; x < 1040; x++) {
          double expected =
              Math.max(0, Math.min(519, x * .5 - .25))
                  + 10 * Math.max(0, Math.min(3, y * .5 - .25));
          assertThat(values[y * 1040 + x])
              .as("pixel %s,%s", x, y)
              .isCloseTo(expected, within(1e-8));
        }
    }
  }

  @Test
  void floatingTypeAndNegativeValuesSurviveRoundtrip() throws Exception {
    var source = new Source();
    source.values[0] = new double[] {-100.25, 0, 10.5, 22.25};
    var req =
        new RasterReprojectRequest(
            "",
            2,
            2,
            null,
            RasterReprojectRequest.Interpolation.BILINEAR,
            RasterReprojectRequest.OutputType.FLOAT32,
            null,
            null,
            dir.resolve("float32.tif"),
            false);
    try (var result = write(source, req)) {
      assertThat(result.dataType()).isEqualTo(DataBuffer.TYPE_FLOAT);
      assertThat(band(result, 0)).containsExactly(-16.875);
    }
  }

  @Test
  void rejectsLongitudeDiscontinuity() throws Exception {
    var source = new Source();
    source.crs = CRS.decode("EPSG:4326", true);
    source.transform = new AffineTransform2D(2, 0, 0, -1, 179, 1.5);
    assertThatThrownBy(
            () -> RasterTargetGrid.create(source, request(dir.resolve("date.tif"), 1, false)))
        .hasMessageContaining("longitude");
  }

  @Test
  void bigTiffPaletteRetainsFullSixteenBitEntries() throws Exception {
    char[] map = {65535, 0, 0, 12345, 0, 65535};
    var color = new RasterColorInfo(RasterColorInfo.Kind.PALETTE, -1, false, map);
    var model = new BandedSampleModel(DataBuffer.TYPE_BYTE, 2, 2, 1);
    var pixels = Raster.createWritableRaster(model, model.createDataBuffer(), new Point());
    pixels.setSamples(0, 0, 2, 2, 0, new int[] {0, 1, 1, 0});
    var image =
        new BufferedImage(
            GeoTiffOutput.colorModel(DataBuffer.TYPE_BYTE, 1, color), pixels, false, null);
    var options = new org.geotools.gce.geotiff.GeoTiffWriteParams();
    options.setForceToBigTIFF(true);
    Path path = dir.resolve("big-palette.tif");
    GeoTiffOutput.write(
        path,
        image,
        CRS.decode("EPSG:3857", true),
        new java.awt.geom.AffineTransform(1, 0, 0, -1, .5, 1.5),
        new double[] {1},
        new double[] {0},
        null,
        color,
        options);
    try (var source = new GeoTiffSource(new RasterDatasetRef(path.toString()))) {
      assertThat((int) source.colorInfo().colorMap()[257]).isEqualTo(12345);
      assertThat(band(source, 0)).containsExactly(0, 1, 1, 0);
    }
    try (var backend = new GeoToolsRasterBackend()) {
      var value = backend.describe(path.toString());
      String box = "POLYGON ((0 0,2 0,2 2,0 2,0 0))";
      var crop =
          backend.derive(
              value,
              new ch.so.agi.hop.raster.RasterOperation.Clip(
                  "palette-crop", box, false, java.util.List.of(0), null));
      assertThat(crop.result().palette()).isEqualTo(value.result().palette());
      backend.write(crop, dir.resolve("palette-crop.tif"), false, () -> false);
      assertThatThrownBy(
              () ->
                  backend.derive(
                      value,
                      new ch.so.agi.hop.raster.RasterOperation.Clip(
                          "palette-mask", box, true, java.util.List.of(0), null)))
          .hasMessageContaining("NoData");
      var mask =
          backend.derive(
              value,
              new ch.so.agi.hop.raster.RasterOperation.Clip(
                  "palette-mask", box, true, java.util.List.of(0), 255d));
      backend.write(mask, dir.resolve("palette-mask.tif"), false, () -> false);
    }
  }

  @Test
  void invalidSettingsAndDisjointExtentFailBeforeOutput() throws Exception {
    var source = new Source();
    assertThatThrownBy(() -> request(dir.resolve("invalid.tif"), 0, false))
        .hasMessageContaining("Pixel sizes");
    var disjoint =
        new RasterReprojectRequest(
            "",
            1,
            1,
            new RasterReprojectRequest.Extent(100, 100, 102, 102),
            RasterReprojectRequest.Interpolation.NEAREST,
            RasterReprojectRequest.OutputType.AUTO,
            null,
            null,
            dir.resolve("disjoint.tif"),
            false);
    assertThatThrownBy(() -> write(source, disjoint)).hasMessageContaining("overlap");
    source.crs = null;
    assertThatThrownBy(() -> write(source, request(dir.resolve("missing-crs.tif"), 1, false)))
        .hasMessageContaining("CRS");
  }
}
