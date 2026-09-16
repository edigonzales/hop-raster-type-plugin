package ch.so.agi.hop.raster;

public final class RasterTile {
  private final RasterWindow window;
  private final java.nio.DoubleBuffer samples;

  /**
   * Takes a read-only view of producer-owned storage. The producer must not mutate or recycle that
   * storage while a tile can still be referenced. Consumers cannot obtain a writable view.
   */
  public RasterTile(RasterWindow window, java.nio.DoubleBuffer samples) {
    this.window = java.util.Objects.requireNonNull(window);
    if ((long) window.width() * window.height() != samples.remaining())
      throw new IllegalArgumentException("Tile size mismatch");
    this.samples = samples.slice().asReadOnlyBuffer();
  }

  public RasterWindow window() {
    return window;
  }

  public java.nio.DoubleBuffer samples() {
    return samples.asReadOnlyBuffer();
  }
}
