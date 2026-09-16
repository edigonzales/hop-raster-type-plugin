package ch.so.agi.hop.raster;

public record RasterBand(String name, int dataType, Double noData, double scale, double offset) {
  public RasterBand {
    java.util.Objects.requireNonNull(name);
    if (dataType < 0 || dataType > 5 || !Double.isFinite(scale) || !Double.isFinite(offset))
      throw new IllegalArgumentException("Invalid band metadata");
  }
}
