package ch.so.agi.hop.raster.geotools;

import ch.so.agi.hop.raster.*;
import java.util.*;
import org.locationtech.jts.geom.*;

final class RasterTestSupport {
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
}
