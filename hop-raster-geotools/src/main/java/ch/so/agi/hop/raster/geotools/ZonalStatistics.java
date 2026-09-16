package ch.so.agi.hop.raster.geotools;

import java.awt.Rectangle;
import java.awt.image.Raster;
import java.util.function.BooleanSupplier;
import org.locationtech.jts.geom.Geometry;

/** Streaming statistics over original pixels. No process-raster dependency. */
public final class ZonalStatistics {
  private ZonalStatistics() {}

  public record Result(
      long count, Double mean, Double min, Double max, Double sum, Double stddev, String status) {
    public Object value(String name) {
      return switch (name) {
        case "count" -> count;
        case "mean" -> mean;
        case "min" -> min;
        case "max" -> max;
        case "sum" -> sum;
        case "stddev" -> stddev;
        default -> throw new IllegalArgumentException(name);
      };
    }
  }

  public static Result compute(RasterSource source, Geometry zone, int band, Double override)
      throws Exception {
    return compute(source, zone, band, override, () -> false);
  }

  public static Result compute(
      RasterSource source, Geometry zone, int band, Double override, BooleanSupplier stopped)
      throws Exception {
    if (band < 0 || band >= source.bands())
      throw new IllegalArgumentException("Band outside raster");
    if (zone == null || zone.isEmpty()) return empty("EMPTY_GEOMETRY");
    PixelMask mask = new PixelMask(source, zone);
    Rectangle w = mask.window();
    long count = 0;
    double mean = 0, m2 = 0, sum = 0, correction = 0;
    double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
    for (int ty = Math.floorDiv(w.y, 512) * 512; ty < (long) w.y + w.height; ty += 512) {
      for (int tx = Math.floorDiv(w.x, 512) * 512; tx < (long) w.x + w.width; tx += 512) {
        GeoTiffSource.checkInterrupted();
        if (stopped.getAsBoolean()) throw new java.io.IOException("Raster operation stopped");
        Rectangle tile = new Rectangle(tx, ty, 512, 512).intersection(source.bounds());
        Rectangle part = tile.intersection(w);
        if (part.isEmpty()) continue;
        Raster raster = source.read(new RasterReadRequest(tile, band));
        for (int y = part.y; y < part.y + part.height; y++) {
          for (int x = part.x; x < part.x + part.width; x++) {
            if (!mask.covers(x, y)) continue;
            double raw = raster.getSampleDouble(x, y, 0);
            if (!valid(source, raw, band, override)) continue;
            double value = source.physical(raw, band);
            if (!Double.isFinite(value)) continue;
            count++;
            double delta = value - mean;
            mean += delta / count;
            m2 += delta * (value - mean);
            double adjusted = value - correction, next = sum + adjusted;
            correction = (next - sum) - adjusted;
            sum = next;
            min = Math.min(min, value);
            max = Math.max(max, value);
          }
        }
      }
    }
    return count == 0
        ? empty("NO_VALID_PIXELS")
        : new Result(count, mean, min, max, sum, Math.sqrt(Math.max(0, m2 / count)), "OK");
  }

  public static boolean valid(RasterSource source, double value, int band, Double override) {
    return Double.isFinite(value)
        && (override == null ? source.valid(value, band) : value != override);
  }

  private static Result empty(String status) {
    return new Result(0, null, null, null, null, null, status);
  }
}
