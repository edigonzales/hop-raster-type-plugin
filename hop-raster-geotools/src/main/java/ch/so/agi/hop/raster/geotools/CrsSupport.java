package ch.so.agi.hop.raster.geotools;

import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Geometry;

public final class CrsSupport {
  private CrsSupport() {}

  public static CoordinateReferenceSystem decode(String code) throws Exception {
    GeoToolsRuntimeSupport.initialize();
    if (code == null || code.isBlank()) throw new IllegalArgumentException("CRS is required");
    String value = code.trim();
    return CRS.decode(value.matches("[0-9]+") ? "EPSG:" + value : value, true);
  }

  public static Geometry inRasterCrs(
      Geometry geometry, String explicitCrs, CoordinateReferenceSystem target) throws Exception {
    if (target == null) throw new IllegalArgumentException("Raster CRS is missing");
    CoordinateReferenceSystem source =
        explicitCrs != null && !explicitCrs.isBlank()
            ? decode(explicitCrs)
            : decode(geometry.getSRID() > 0 ? "EPSG:" + geometry.getSRID() : "");
    Geometry copy = geometry.copy();
    return CRS.equalsIgnoreMetadata(source, target)
        ? copy
        : JTS.transform(copy, CRS.findMathTransform(source, target, false));
  }
}
