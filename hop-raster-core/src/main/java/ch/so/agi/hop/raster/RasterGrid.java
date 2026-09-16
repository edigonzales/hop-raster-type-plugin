package ch.so.agi.hop.raster;

public record RasterGrid(
    int width, int height, double m00, double m10, double m01, double m11, double m02, double m12) {
  public RasterGrid {
    if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid grid size");
    for (double v : new double[] {m00, m10, m01, m11, m02, m12})
      if (!Double.isFinite(v)) throw new IllegalArgumentException("Non-finite grid");
    if (m00 * m11 - m01 * m10 == 0) throw new IllegalArgumentException("Singular grid");
  }
}
