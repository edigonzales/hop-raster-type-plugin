package ch.so.agi.hop.raster.geotools;

import com.atolcd.hop.gis.geometry.curve.CurveGeometrySupport;
import java.awt.Rectangle;
import org.geotools.geometry.jts.JTS;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;

/** Geometry in grid-centre coordinates; boundary centres are included. */
public final class PixelMask {
  private final Geometry gridGeometry;
  private final PreparedGeometry prepared;
  private final Rectangle window;

  public static void validate(Geometry geometry) {
    if (CurveGeometrySupport.isCurveGeometry(geometry))
      throw new IllegalArgumentException("Curved zones must be explicitly linearized upstream");
    if (!(geometry instanceof Polygon || geometry instanceof MultiPolygon))
      throw new IllegalArgumentException("Zone must be Polygon or MultiPolygon");
    if (!geometry.isValid()) throw new IllegalArgumentException("Invalid polygon zone");
    for (Coordinate c : geometry.getCoordinates())
      if (!Double.isFinite(c.x) || !Double.isFinite(c.y))
        throw new IllegalArgumentException("Non-finite zone coordinates");
  }

  public PixelMask(RasterSource source, Geometry rasterGeometry) throws Exception {
    validate(rasterGeometry);
    gridGeometry = JTS.transform(rasterGeometry, source.gridToWorld().inverse());
    prepared = PreparedGeometryFactory.prepare(gridGeometry);
    var e = gridGeometry.getEnvelopeInternal();
    Rectangle bounds = source.bounds();
    // Clamp in double precision before converting to int, including very distant zones.
    int x0 =
        (int)
            Math.max(
                bounds.x, Math.min((double) bounds.x + bounds.width, Math.floor(e.getMinX() + .5)));
    int y0 =
        (int)
            Math.max(
                bounds.y,
                Math.min((double) bounds.y + bounds.height, Math.floor(e.getMinY() + .5)));
    int x1 =
        (int)
            Math.max(
                bounds.x, Math.min((double) bounds.x + bounds.width, Math.ceil(e.getMaxX() + .5)));
    int y1 =
        (int)
            Math.max(
                bounds.y, Math.min((double) bounds.y + bounds.height, Math.ceil(e.getMaxY() + .5)));
    window = new Rectangle(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
  }

  public Rectangle window() {
    return new Rectangle(window);
  }

  public boolean covers(int x, int y) {
    return prepared.covers(gridGeometry.getFactory().createPoint(new Coordinate(x, y)));
  }
}
