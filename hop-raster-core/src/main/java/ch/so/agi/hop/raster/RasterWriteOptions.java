package ch.so.agi.hop.raster;

/** Backend-neutral raster write request: output format, compression and overview behavior. */
public record RasterWriteOptions(
    Format format, Overviews overviews, Resampling resampling, int blockSize, String compression) {
  public enum Format {
    GEOTIFF,
    COG
  }

  public enum Overviews {
    NONE,
    AUTO
  }

  public enum Resampling {
    NEAREST,
    AVERAGE
  }

  public RasterWriteOptions {
    format = format == null ? Format.GEOTIFF : format;
    overviews = overviews == null ? Overviews.AUTO : overviews;
    resampling = resampling == null ? Resampling.AVERAGE : resampling;
    compression = compression == null || compression.isBlank() ? "Deflate" : compression;
    if (blockSize < 16 || blockSize > 4096 || Integer.bitCount(blockSize) != 1)
      throw new IllegalArgumentException("Block size must be a power of two between 16 and 4096");
  }

  /** Plain tiled GeoTIFF; overview and resampling settings do not apply. */
  public static RasterWriteOptions geoTiff(String compression) {
    return new RasterWriteOptions(
        Format.GEOTIFF, Overviews.NONE, Resampling.NEAREST, 512, compression);
  }

  /** Cloud Optimized GeoTIFF with 512-pixel tiles and the backend's default overview resampling. */
  public static RasterWriteOptions cog(String compression) {
    return new RasterWriteOptions(Format.COG, Overviews.AUTO, Resampling.AVERAGE, 512, compression);
  }
}
