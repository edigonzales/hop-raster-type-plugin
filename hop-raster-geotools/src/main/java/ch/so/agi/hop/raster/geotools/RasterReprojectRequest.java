package ch.so.agi.hop.raster.geotools;

import java.nio.file.Path;

/** Resolutions and optional extent are in target CRS units; null CRS means source CRS. */
public record RasterReprojectRequest(
    String targetCrs,
    double resolutionX,
    double resolutionY,
    Extent extent,
    Interpolation interpolation,
    OutputType outputType,
    Double sourceNoData,
    Double outputNoData,
    Path output,
    boolean overwrite) {
  public enum Interpolation {
    NEAREST,
    BILINEAR
  }

  public enum OutputType {
    AUTO,
    SOURCE,
    FLOAT32,
    FLOAT64
  }

  public record Extent(double minX, double minY, double maxX, double maxY) {
    public Extent {
      if (!Double.isFinite(minX)
          || !Double.isFinite(minY)
          || !Double.isFinite(maxX)
          || !Double.isFinite(maxY)
          || minX >= maxX
          || minY >= maxY)
        throw new IllegalArgumentException(
            "Bounding box requires finite minimum < maximum coordinates");
    }
  }

  public RasterReprojectRequest {
    if (!(resolutionX > 0)
        || !(resolutionY > 0)
        || !Double.isFinite(resolutionX)
        || !Double.isFinite(resolutionY))
      throw new IllegalArgumentException("Pixel sizes must be positive and finite");
    if (interpolation == null || outputType == null || output == null)
      throw new IllegalArgumentException("Interpolation, output type and file are required");
    if ((sourceNoData != null && Double.isInfinite(sourceNoData))
        || (outputNoData != null && Double.isInfinite(outputNoData)))
      throw new IllegalArgumentException("NoData cannot be infinite");
  }
}
