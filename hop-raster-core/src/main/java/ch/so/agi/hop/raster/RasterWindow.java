package ch.so.agi.hop.raster;

public record RasterWindow(int x, int y, int width, int height) {
  public RasterWindow {
    if (x < 0
        || y < 0
        || width <= 0
        || height <= 0
        || (long) x + width > Integer.MAX_VALUE
        || (long) y + height > Integer.MAX_VALUE)
      throw new IllegalArgumentException("Invalid raster window");
  }
}
