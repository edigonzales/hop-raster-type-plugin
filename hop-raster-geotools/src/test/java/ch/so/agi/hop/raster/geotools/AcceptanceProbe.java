package ch.so.agi.hop.raster.geotools;

import ch.so.agi.hop.raster.*;
import java.nio.file.*;
import java.util.*;
import org.locationtech.jts.geom.*;

/** Bounded real-data and fixed-heap probe, independent of benchmark timings. */
public final class AcceptanceProbe {
  static Polygon region(RasterGrid g, int x, int y, int size) {
    Coordinate[] points = new Coordinate[5];
    int[][] corners = {{x, y}, {x + size, y}, {x + size, y + size}, {x, y + size}, {x, y}};
    for (int i = 0; i < 5; i++) {
      double px = corners[i][0] - .5, py = corners[i][1] - .5;
      points[i] =
          new Coordinate(
              g.m00() * px + g.m01() * py + g.m02(), g.m10() * px + g.m11() * py + g.m12());
    }
    return new GeometryFactory().createPolygon(points);
  }

  static Object field(Object value, String name) throws Exception {
    var field = value.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(value);
  }

  static void resources(GeoToolsRasterBackend backend, int sessions) throws Exception {
    if (((Set<?>) field(backend, "sessions")).size() != sessions)
      throw new AssertionError("Session leak");
    for (Object session : (Set<?>) field(backend, "sessions")) {
      for (String name : List.of("outputCache", "operationCache")) {
        var cache = (org.eclipse.imagen.media.util.SunTileCache) field(session, name);
        if (cache.getCacheMemoryUsed() > cache.getMemoryCapacity())
          throw new AssertionError("Operation cache exceeded");
      }
    }
    var raw = (GeoTiffSource) field(backend, "source");
    if (raw != null && raw.cachedBytes() > 64L * 1024 * 1024)
      throw new AssertionError("Source cache exceeded");
  }

  public static void main(String[] args) throws Exception {
    String source = args[0];
    Path output = Path.of(args[1]);
    Files.createDirectories(output);
    int repetitions = args.length > 2 ? Integer.parseInt(args[2]) : 100;
    try (var backend = new GeoToolsRasterBackend()) {
      var value = backend.describe(source);
      var g = value.result().grid();
      int selectedX = -1, selectedY = -1;
      try (var session = backend.session(value, () -> false)) {
        search:
        for (int gy = 1; gy <= 7; gy++)
          for (int gx = 1; gx <= 7; gx++) {
            int x = (g.width() - 32) * gx / 8, y = (g.height() - 32) * gy / 8;
            var raster =
                session
                    .source()
                    .read(new RasterReadRequest(new java.awt.Rectangle(x, y, 32, 32), 0));
            for (int j = y; j < y + 32; j++)
              for (int i = x; i < x + 32; i++)
                if (session.source().valid(raster.getSampleDouble(i, j, 0), 0)) {
                  selectedX = x;
                  selectedY = y;
                  break search;
                }
          }
      }
      if (selectedX < 0) throw new AssertionError("No valid sample in fixed search grid");
      System.out.println(
          "SELECTED " + selectedX + " " + selectedY + " GRID " + g.width() + " " + g.height());
      for (int requestedSize : new int[] {128, 1024}) {
        int size = Math.min(requestedSize, Math.min(g.width(), g.height()));
        int x = Math.min(selectedX, g.width() - size), y = Math.min(selectedY, g.height() - size);
        var polygon = region(g, x, y, size);
        var clipped =
            backend.derive(
                value,
                new RasterOperation.Clip(
                    "probe-clip-" + size,
                    polygon.toText(),
                    false,
                    java.util.stream.IntStream.range(0, value.result().bands().size())
                        .boxed()
                        .toList(),
                    null));
        double rx = Math.hypot(g.m00(), g.m10()) * 2, ry = Math.hypot(g.m01(), g.m11()) * 2;
        var warped =
            backend.derive(
                clipped,
                new RasterOperation.Reproject(
                    "probe-warp",
                    value.result().authority(),
                    rx,
                    ry,
                    List.of(),
                    "BILINEAR",
                    "AUTO",
                    null,
                    null));
        backend.write(warped, output.resolve("clip-warp-" + size + ".tif"), true, () -> false);
        var firstBand =
            backend.derive(
                value,
                new RasterOperation.Clip(
                    "reference-band1",
                    polygon.toText(),
                    false,
                    List.of(0),
                    value.result().bands().size() > 1 ? 0d : null));
        var firstWarp =
            backend.derive(
                firstBand,
                new RasterOperation.Reproject(
                    "reference-warp",
                    value.result().authority(),
                    rx,
                    ry,
                    List.of(),
                    "BILINEAR",
                    "AUTO",
                    null,
                    null));
        backend.write(firstWarp, output.resolve("band1-" + size + ".tif"), true, () -> false);
        if (value.result().bands().size() == 1)
          try (var session = backend.session(clipped, () -> false)) {
            var stats = ZonalStatistics.compute(session.source(), polygon, 0, null);
            if (stats.count() == 0) throw new AssertionError("No valid zone pixels");
            System.out.println("STATS " + stats);
          }
      }
      for (int i = 0; i < repetitions; i++) {
        int x = Math.min(selectedX + i % 32, g.width() - 64),
            y = Math.min(selectedY + i / 32 % 32, g.height() - 64);
        var clipped =
            backend.derive(
                value,
                new RasterOperation.Clip(
                    "repeat-" + i,
                    region(g, x, y, 64).toText(),
                    false,
                    List.of(0),
                    value.result().bands().size() > 1 ? 0d : null));
        try (var session = backend.session(clipped, () -> false)) {
          session.read(new RasterWindow(0, 0, 32, 32), 0);
          resources(backend, 1);
        }
        resources(backend, 0);
      }
      backend.close();
      resources(backend, 0);
      if (field(backend, "source") != null) throw new AssertionError("Source not released");
      System.out.println("PROBE_PASS rows=" + repetitions);
    }
  }
}
