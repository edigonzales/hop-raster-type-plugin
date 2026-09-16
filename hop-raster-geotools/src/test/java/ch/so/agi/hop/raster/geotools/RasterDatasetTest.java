package ch.so.agi.hop.raster.geotools;

import static org.assertj.core.api.Assertions.*;

import ch.so.agi.hop.raster.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RasterDatasetTest {
  @TempDir Path dir;

  @Test
  void lazyChainMatchesMaterializedStagesAndSurvivesProducerDisposal() throws Exception {
    var input = RasterCoreTest.fixture(dir.resolve("input.tif"));
    var box = RasterCoreTest.box(2600001, 1200200, 2600100, 1200255);
    var clip = new RasterOperation.Clip("clip", box.toText(), true, List.of(0), null);
    var warp =
        new RasterOperation.Reproject(
            "warp", "EPSG:2056", .5, .5, List.of(), "BILINEAR", "FLOAT32", null, null);
    RasterDataset chain;
    try (var backend = new GeoToolsRasterBackend()) {
      var source = backend.describe(input.toString());
      chain = backend.derive(backend.derive(source, clip), warp);
      assertThat(Files.list(dir).count()).isEqualTo(1);
      chain = RasterCodec.decode(RasterCodec.encode(chain));
    }
    try (var source = new GeoTiffSource(new RasterDatasetRef(input.toString()))) {
      RasterClip.write(source, box, true, 0, null, dir.resolve("clip.tif"), false, () -> false);
    }
    try (var source = new GeoTiffSource(new RasterDatasetRef(dir.resolve("clip.tif").toString()))) {
      var req = DerivedSource.request(warp);
      RasterReproject.write(
          source,
          new RasterReprojectRequest(
              req.targetCrs(),
              req.resolutionX(),
              req.resolutionY(),
              req.extent(),
              req.interpolation(),
              req.outputType(),
              null,
              null,
              dir.resolve("baseline.tif"),
              false),
          () -> false);
    }
    try (var backend = new GeoToolsRasterBackend()) {
      backend.write(chain, dir.resolve("candidate.tif"), false, () -> false);
    }
    try (var a = new GeoTiffSource(new RasterDatasetRef(dir.resolve("baseline.tif").toString()));
        var b = new GeoTiffSource(new RasterDatasetRef(dir.resolve("candidate.tif").toString()))) {
      assertThat(GeoToolsRasterBackend.descriptor(a))
          .isEqualTo(GeoToolsRasterBackend.descriptor(b));
      var request = new RasterReadRequest(a.bounds(), 0);
      assertThat(
              a.read(request)
                  .getSamples(0, 0, a.bounds().width, a.bounds().height, 0, (double[]) null))
          .containsExactly(
              b.read(request)
                  .getSamples(0, 0, b.bounds().width, b.bounds().height, 0, (double[]) null));
    }
  }

  @Test
  void rgbClipAddsAlphaAndRetainsColor() throws Exception {
    var input =
        RasterColorFixture.write(
            dir.resolve("rgb.tif"), 2, 0, new int[][] {{10, 20, 30}, {40, 50, 60}, {70, 80, 90}});
    try (var backend = new GeoToolsRasterBackend()) {
      var value = backend.describe(input.toString());
      var operation =
          new RasterOperation.Clip(
              "mask", "POLYGON ((0 0,3 0,3 1,0 0))", true, List.of(0, 1, 2), null);
      var derived = backend.derive(value, operation);
      assertThat(derived.result().alphaBand()).isEqualTo(3);
      assertThat(derived.result().bands()).hasSize(4);
      backend.write(derived, dir.resolve("out.tif"), false, () -> false);
    }
  }

  @Test
  void protectsSourcesAndDetectsChanges() throws Exception {
    var input = RasterCoreTest.fixture(dir.resolve("source.tif"));
    try (var backend = new GeoToolsRasterBackend()) {
      var value = backend.describe(input.toString());
      assertThatThrownBy(() -> backend.write(value, input, true, () -> false))
          .hasMessageContaining("differ");
      Files.setLastModifiedTime(
          input, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10000));
      assertThatThrownBy(() -> backend.open(value, () -> false)).hasMessageContaining("changed");
    }
  }

  @Test
  void planningDoesNotReopenSourceAndBranchesOwnTheirSessions() throws Exception {
    var input = RasterCoreTest.fixture(dir.resolve("planning.tif"));
    RasterDataset value;
    try (var reader = new GeoToolsRasterBackend()) {
      value = reader.describe(input.toString());
    }
    Path moved = Files.move(input, dir.resolve("moved.tif"));
    var op =
        new RasterOperation.Clip(
            "metadata-only",
            RasterCoreTest.box(2600000, 1200255, 2600001, 1200256).toText(),
            false,
            List.of(0),
            null);
    try (var planner = new GeoToolsRasterBackend()) {
      value = planner.derive(value, op);
    }
    Files.move(moved, input);
    try (var a = new GeoToolsRasterBackend();
        var b = new GeoToolsRasterBackend()) {
      var first = a.open(value, () -> false);
      var second = b.open(value, () -> false);
      var tile = first.read(new RasterWindow(0, 0, 2, 2), 0);
      assertThat(tile.samples().isReadOnly()).isTrue();
      first.close();
      a.close();
      assertThat(second.read(new RasterWindow(0, 0, 2, 2), 0).samples().get(0))
          .isEqualTo(tile.samples().get(0));
      second.close();
    }
  }

  @Test
  void cancelledWriterKeepsExistingTargetAndCleansTemporaryFiles() throws Exception {
    var input = RasterCoreTest.fixture(dir.resolve("cancel-source.tif"));
    Path target = dir.resolve("keep.tif");
    byte[] before = {1, 2, 3};
    Files.write(target, before);
    try (var backend = new GeoToolsRasterBackend()) {
      var value = backend.describe(input.toString());
      assertThatThrownBy(() -> backend.write(value, target, true, () -> true))
          .isInstanceOf(java.io.IOException.class);
    }
    assertThat(Files.readAllBytes(target)).isEqualTo(before);
    try (var paths = Files.list(dir)) {
      assertThat(paths.filter(p -> p.getFileName().toString().startsWith(".hop-raster-")).toList())
          .isEmpty();
    }
  }

  @Test
  void selectedBandsAreOrderedNumericAndExistingAlphaIsMasked() throws Exception {
    var input =
        RasterColorFixture.write(
            dir.resolve("rgba.tif"),
            2,
            2,
            new int[][] {{10, 20, 30}, {40, 50, 60}, {70, 80, 90}, {255, 128, 64}});
    try (var backend = new GeoToolsRasterBackend()) {
      var value = backend.describe(input.toString());
      var rgb =
          backend.derive(
              value,
              new RasterOperation.Clip(
                  "RGB subset", "POLYGON ((0 0,3 0,3 1,0 1,0 0))", false, List.of(0, 1, 2), null));
      assertThat(rgb.result().color()).isEqualTo("RGB");
      assertThat(rgb.result().alphaBand()).isEqualTo(-1);
      var subset =
          backend.derive(
              value,
              new RasterOperation.Clip(
                  "subset", "POLYGON ((0 0,3 0,3 1,0 1,0 0))", false, List.of(2, 0), 255d));
      assertThat(subset.result().color()).isEqualTo("NUMERIC");
      try (var r = backend.open(subset, () -> false)) {
        assertThat(r.read(new RasterWindow(0, 0, 3, 1), 0).samples().get(0)).isEqualTo(70);
        assertThat(r.read(new RasterWindow(0, 0, 3, 1), 1).samples().get(2)).isEqualTo(30);
      }
      var masked =
          backend.derive(
              value,
              new RasterOperation.Clip(
                  "mask", "POLYGON ((0 0,3 0,3 1,0 0))", true, List.of(0, 1, 2, 3), null));
      try (var r = backend.open(masked, () -> false)) {
        var alpha = r.read(new RasterWindow(0, 0, 3, 1), 3).samples();
        assertThat(alpha.get(0)).isZero();
        assertThat(alpha.get(1)).isEqualTo(128);
        assertThat(alpha.get(2)).isEqualTo(64);
      }
    }
  }

  @Test
  void colorClipRetainsOrConvertsNoDataIntoAlpha() throws Exception {
    var file =
        RasterColorFixture.write(
            dir.resolve("color-nodata.tif"), 2, 0, new int[][] {{0, 10}, {0, 20}, {0, 30}}, 0);
    try (var backend = new GeoToolsRasterBackend()) {
      var source = backend.describe(file.toString());
      String polygon = "POLYGON ((0 0,2 0,2 1,0 1,0 0))";
      var box =
          backend.derive(
              source, new RasterOperation.Clip("box", polygon, false, List.of(0, 1, 2), null));
      assertThat(box.result().bands().get(0).noData()).isEqualTo(0d);
      var mask =
          backend.derive(
              source, new RasterOperation.Clip("mask", polygon, true, List.of(0, 1, 2), null));
      try (var reader = backend.open(mask, () -> false)) {
        var alpha = reader.read(new RasterWindow(0, 0, 2, 1), 3).samples();
        assertThat(alpha.get(0)).isZero();
        assertThat(alpha.get(1)).isEqualTo(255);
      }
    }
  }
}
