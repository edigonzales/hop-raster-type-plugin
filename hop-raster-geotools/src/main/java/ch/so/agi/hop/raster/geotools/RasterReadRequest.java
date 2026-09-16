package ch.so.agi.hop.raster.geotools;

import java.awt.Rectangle;

/** Pixel coordinates in the original grid; band is zero based. */
public record RasterReadRequest(Rectangle window, int band) {
  public RasterReadRequest {
    if (window == null || window.isEmpty() || band < 0)
      throw new IllegalArgumentException("Invalid raster window or band");
    window = new Rectangle(window);
  }

  @Override
  public Rectangle window() {
    return new Rectangle(window);
  }
}
