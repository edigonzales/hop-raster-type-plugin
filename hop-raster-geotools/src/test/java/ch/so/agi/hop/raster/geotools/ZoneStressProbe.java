package ch.so.agi.hop.raster.geotools;

import java.nio.file.Path;

/** Many distinct zones, with independent pixel-formula expectations and reader reuse checks. */
public final class ZoneStressProbe {
  public static void main(String[] args) throws Exception {
    int repetitions = Integer.parseInt(args[1]);
    try (var backend = new GeoToolsRasterBackend()) {
      var value = backend.describe(Path.of(args[0]).toString());
      var grid = value.result().grid();
      Object original = AcceptanceProbe.field(backend, "source");
      for (int i = 0; i < repetitions; i++) {
        int x = i % 100, y = i / 100;
        double sum = 0, squares = 0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        long count = 0;
        for (int py = y; py < y + 16; py++) {
          for (int px = x; px < x + 16; px++) {
            if ((px + py) % 113 == 0) continue;
            double sample = (float) (100 + Math.sin(px * .03) * 35 + Math.cos(py * .04) * 29);
            sum += sample;
            squares += sample * sample;
            min = Math.min(min, sample);
            max = Math.max(max, sample);
            count++;
          }
        }
        try (var session = backend.session(value, () -> false)) {
          var actual =
              ZonalStatistics.compute(
                  session.source(), AcceptanceProbe.region(grid, x, y, 16), 0, null);
          double deviation =
              Math.sqrt(Math.max(0, squares / count - (sum / count) * (sum / count)));
          if (actual.count() != count
              || actual.min() != min
              || actual.max() != max
              || Math.abs(actual.sum() - sum) > 1e-6 * Math.max(1, Math.abs(sum))
              || Math.abs(actual.mean() - sum / count) > 1e-6 * Math.max(1, Math.abs(sum / count))
              || Math.abs(actual.stddev() - deviation) > 1e-6 * Math.max(1, actual.stddev())
              || !"OK".equals(actual.status())) {
            throw new AssertionError("Incorrect zone " + i + ": " + actual);
          }
          AcceptanceProbe.resources(backend, 1);
        }
        AcceptanceProbe.resources(backend, 0);
        if (AcceptanceProbe.field(backend, "source") != original)
          throw new AssertionError("Source reader was reopened");
      }
      backend.close();
      AcceptanceProbe.resources(backend, 0);
      if (AcceptanceProbe.field(backend, "source") != null)
        throw new AssertionError("Reader leaked");
      System.out.println("ZONES_PASS rows=" + repetitions);
    }
  }
}
