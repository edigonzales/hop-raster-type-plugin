package ch.so.agi.hop.raster.geotools;

import static org.assertj.core.api.Assertions.*;

import java.awt.Rectangle;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.nio.file.Files;
import java.nio.file.Path;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.referencing.CRS;
import org.geotools.referencing.operation.transform.AffineTransform2D;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;

class RasterSemanticsTest {
  @TempDir Path dir;

  private static final class Source implements RasterSource {
    private final CoordinateReferenceSystem crs = CRS.decode("EPSG:2056", true);

    Source() throws Exception {}

    public Rectangle bounds() {
      return new Rectangle(0, 0, 4, 3);
    }

    public CoordinateReferenceSystem crs() {
      return crs;
    }

    public MathTransform gridToWorld() {
      return new AffineTransform2D(1, 0, 0, -1, 2600000.5, 1200002.5);
    }

    public int bands() {
      return 2;
    }

    public Raster read(RasterReadRequest request) {
      Rectangle r = request.window();
      var data =
          Raster.createBandedRaster(DataBuffer.TYPE_USHORT, r.width, r.height, 1, r.getLocation());
      for (int y = r.y; y < r.y + r.height; y++)
        for (int x = r.x; x < r.x + r.width; x++)
          data.setSample(x, y, 0, request.band() == 1 ? 10 : y == 0 && x == 0 ? 65535 : y * 4 + x);
      return data;
    }

    public Double noData(int band) {
      return 65535d;
    }

    public boolean valid(double value, int band) {
      return Double.isFinite(value) && value != 65535;
    }

    public double physical(double value, int band) {
      return value * scale(band) + offset(band);
    }

    public double scale(int band) {
      return .5;
    }

    public double offset(int band) {
      return 100;
    }

    public int dataType() {
      return DataBuffer.TYPE_USHORT;
    }

    public void close() {}
  }

  @Test
  void noDataScaleOffsetBandsAndIntegerClip() throws Exception {
    try (var source = new Source()) {
      Geometry zone = RasterCoreTest.box(2600000, 1200000, 2600004, 1200003);
      var stats = ZonalStatistics.compute(source, zone, 0, null);
      assertThat(stats.count()).isEqualTo(11);
      assertThat(stats.mean()).isEqualTo(103);
      assertThat(ZonalStatistics.compute(source, zone, 1, null).mean()).isEqualTo(105);
      // Override replaces the source sentinel; zero must not be treated as NoData by default.
      assertThat(ZonalStatistics.compute(source, zone, 1, 10d).count()).isZero();
      Path output = dir.resolve("scaled.tif");
      RasterClip.write(source, zone, true, 0, null, output, false, () -> false);
      try (var copy = new GeoTiffSource(new RasterDatasetRef(output.toString()))) {
        assertThat(copy.dataType()).isEqualTo(DataBuffer.TYPE_USHORT);
        assertThat(copy.scale(0)).isEqualTo(.5);
        assertThat(copy.offset(0)).isEqualTo(100);
        assertThat(copy.read(new RasterReadRequest(copy.bounds(), 0)).getSampleDouble(3, 2, 0))
            .isEqualTo(11);
        assertThat(ZonalStatistics.compute(copy, zone, 0, null).mean()).isEqualTo(103);
      }
      Path cancelled = dir.resolve("cancelled.tif");
      assertThatThrownBy(
              () -> RasterClip.write(source, zone, true, 0, null, cancelled, false, () -> true))
          .isInstanceOf(Exception.class);
      assertThat(cancelled).doesNotExist();
      try (var files = Files.list(dir)) {
        assertThat(files.map(p -> p.getFileName().toString()).toList())
            .containsExactly("scaled.tif");
      }
    }
  }

  @Test
  void centreBoundaryHolesMultipartAndCrs() throws Exception {
    try (var source = new Source()) {
      Geometry full = RasterCoreTest.box(2600000, 1200000, 2600004, 1200003);
      Geometry hole = RasterCoreTest.box(2600001, 1200001, 2600003, 1200002);
      Geometry donut = full.difference(hole);
      assertThat(ZonalStatistics.compute(source, donut, 1, null).count()).isEqualTo(10);
      Geometry multi =
          RasterCoreTest.box(2600000, 1200000, 2600001, 1200001)
              .union(RasterCoreTest.box(2600003, 1200002, 2600004, 1200003));
      assertThat(ZonalStatistics.compute(source, multi, 1, null).count()).isEqualTo(2);
      // The centre lies exactly on the polygon corner; covers includes the boundary.
      assertThat(
              ZonalStatistics.compute(
                      source, RasterCoreTest.box(2600000.5, 1200000.5, 2600001, 1200001), 1, null)
                  .count())
          .isEqualTo(1);
      full.setSRID(2056);
      Geometry geographic =
          CrsSupport.inRasterCrs(full, "EPSG:2056", CRS.decode("EPSG:4326", true));
      geographic.setSRID(4326);
      String original = geographic.toText();
      Geometry projected = CrsSupport.inRasterCrs(geographic, "", source.crs());
      assertThat(ZonalStatistics.compute(source, projected, 1, null).count()).isEqualTo(12);
      assertThat(geographic.toText()).isEqualTo(original);
      assertThat(geographic.getSRID()).isEqualTo(4326);
      assertThatThrownBy(() -> CrsSupport.inRasterCrs(hole, "", source.crs()))
          .hasMessageContaining("CRS");
      Geometry invalid = new WKTReader().read("POLYGON ((0 0, 2 2, 0 2, 2 0, 0 0))");
      assertThatThrownBy(() -> ZonalStatistics.compute(source, invalid, 0, null))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void zeroIsValidAndNonzeroWindowIsAligned() throws Exception {
    try (var source =
        new GeoTiffSource(
            new RasterDatasetRef(RasterCoreTest.fixture(dir.resolve("input.tif")).toString()))) {
      var zero =
          ZonalStatistics.compute(
              source, RasterCoreTest.box(2600001.25, 1200255.75, 2600001.5, 1200256), 0, null);
      assertThat(zero.count()).isEqualTo(1);
      assertThat(zero.mean()).isZero();
      var r = source.read(new RasterReadRequest(new Rectangle(2, 1, 2, 2), 0));
      assertThat(r.getMinX()).isEqualTo(2);
      assertThat(r.getSampleDouble(3, 2, 0)).isEqualTo(12);
    }
  }
}
