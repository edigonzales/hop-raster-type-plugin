package ch.so.agi.hop.raster.geotools;

import java.awt.Rectangle;

/** Blockwise acceptance comparison; exact samples, including intermediate resampling rounding. */
public final class BenchmarkCompare {
  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 3)
      throw new IllegalArgumentException("reference.tif candidate.tif");
    double tolerance = args.length == 3 ? Double.parseDouble(args[2]) : 0;
    if (!Double.isFinite(tolerance) || tolerance < 0 || tolerance > 1e-6)
      throw new IllegalArgumentException("Invalid tolerance");
    try (var expected = new GeoTiffSource(new RasterDatasetRef(args[0]));
        var actual = new GeoTiffSource(new RasterDatasetRef(args[1]))) {
      var a = GeoToolsRasterBackend.descriptor(expected);
      var b = GeoToolsRasterBackend.descriptor(actual);
      if (!a.equals(b)) throw new AssertionError("Raster descriptors differ: " + a + " / " + b);
      Rectangle bounds = expected.bounds();
      long samples = 0;
      for (int y = 0; y < bounds.height; y += 512) {
        for (int x = 0; x < bounds.width; x += 512) {
          var window =
              new Rectangle(
                  x, y, Math.min(512, bounds.width - x), Math.min(512, bounds.height - y));
          for (int band = 0; band < expected.bands(); band++) {
            var request = new RasterReadRequest(window, band);
            var left = expected.read(request);
            var right = actual.read(request);
            for (int j = y; j < y + window.height; j++)
              for (int i = x; i < x + window.width; i++) {
                double v = left.getSampleDouble(i, j, 0), w = right.getSampleDouble(i, j, 0);
                if (expected.valid(v, band) != actual.valid(w, band)
                    || (Double.doubleToLongBits(v) != Double.doubleToLongBits(w)
                        && !(tolerance > 0
                            && Double.isFinite(v)
                            && Double.isFinite(w)
                            && Math.abs(v - w) <= tolerance * Math.max(1, Math.abs(v)))))
                  throw new AssertionError(
                      "Sample mismatch at "
                          + i
                          + ","
                          + j
                          + ", band "
                          + band
                          + ": "
                          + v
                          + " / "
                          + w);
                samples++;
              }
          }
        }
      }
      System.out.println(
          "Equal grid, CRS, bands, color, NoData, scale/offset and "
              + samples
              + " samples; tolerance="
              + tolerance);
    }
  }
}
