package ch.so.agi.hop.raster.geotools;

/** TIFF sample interpretation, independent of the number of bands. Palette channels are UInt16. */
public record RasterColorInfo(Kind kind, int alphaBand, boolean associatedAlpha, char[] colorMap) {
  public enum Kind {
    NUMERIC,
    RGB,
    GRAY_ALPHA,
    PALETTE,
    UNSUPPORTED
  }

  public RasterColorInfo {
    colorMap = colorMap == null ? null : colorMap.clone();
  }

  @Override
  public char[] colorMap() {
    return colorMap == null ? null : colorMap.clone();
  }

  public static RasterColorInfo numeric() {
    return new RasterColorInfo(Kind.NUMERIC, -1, false, null);
  }
}
