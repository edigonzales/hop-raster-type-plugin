package ch.so.agi.hop.raster;

public record RasterDescriptor(
    RasterGrid grid,
    String crsWkt,
    String authority,
    java.util.List<RasterBand> bands,
    String color,
    int alphaBand,
    boolean associatedAlpha,
    java.util.List<Integer> palette) {
  public RasterDescriptor {
    java.util.Objects.requireNonNull(grid);
    java.util.Objects.requireNonNull(color);
    bands = java.util.List.copyOf(bands);
    palette = java.util.List.copyOf(palette);
    if (bands.isEmpty() || alphaBand < -1 || alphaBand >= bands.size())
      throw new IllegalArgumentException("Invalid bands");
  }
}
