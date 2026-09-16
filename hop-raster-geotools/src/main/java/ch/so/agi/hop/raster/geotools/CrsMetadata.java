package ch.so.agi.hop.raster.geotools;

import java.util.LinkedHashMap;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.referencing.CRS;

/** Small process-wide metadata cache. It retains no datasets, pixels, sessions or credentials. */
final class CrsMetadata {
  private static final int LIMIT = 32;
  private static final LinkedHashMap<String, CoordinateReferenceSystem> CACHE =
      new LinkedHashMap<>(16, .75f, true);

  private CrsMetadata() {}

  static synchronized String wkt(CoordinateReferenceSystem crs) {
    if (crs == null) return null;
    for (var entry : CACHE.entrySet()) if (entry.getValue() == crs) return entry.getKey();
    String wkt = crs.toWKT();
    remember(wkt, crs);
    return wkt;
  }

  static synchronized CoordinateReferenceSystem parse(String wkt) throws Exception {
    if (wkt == null) return null;
    var crs = CACHE.get(wkt);
    if (crs != null) return crs;
    crs = CRS.parseWKT(wkt);
    remember(wkt, crs);
    return crs;
  }

  private static void remember(String wkt, CoordinateReferenceSystem crs) {
    CACHE.put(wkt, crs);
    while (CACHE.size() > LIMIT) CACHE.remove(CACHE.keySet().iterator().next());
  }
}
