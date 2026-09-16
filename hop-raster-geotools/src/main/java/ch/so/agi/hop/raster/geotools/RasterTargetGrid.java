package ch.so.agi.hop.raster.geotools;

import java.awt.Rectangle;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.crs.GeographicCRS;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.referencing.CRS;
import org.geotools.referencing.operation.transform.AffineTransform2D;
import org.locationtech.jts.geom.*;

/** A zero-origin, north-up output grid whose outer edges are resolution multiples. */
public record RasterTargetGrid(
    int width,
    int height,
    CoordinateReferenceSystem crs,
    AffineTransform2D gridToWorld,
    MathTransform targetToSource) {
  public static RasterTargetGrid create(RasterSource source, RasterReprojectRequest request)
      throws Exception {
    if (source.crs() == null) throw new IllegalArgumentException("Raster CRS is missing");
    var target =
        request.targetCrs() == null || request.targetCrs().isBlank()
            ? source.crs()
            : CrsSupport.decode(request.targetCrs());
    var forward = CRS.findMathTransform(source.crs(), target, false);
    var inverse = CRS.findMathTransform(target, source.crs(), false);
    Rectangle b = source.bounds();
    // Densify actual pixel edges (including rotated grids), not their axis-aligned envelope.
    Coordinate[] edge = new Coordinate[4 * 256 + 1];
    double[][] corners = {
      {b.x - .5, b.y - .5},
      {b.getMaxX() - .5, b.y - .5},
      {b.getMaxX() - .5, b.getMaxY() - .5},
      {b.x - .5, b.getMaxY() - .5}
    };
    for (int side = 0; side < 4; side++)
      for (int i = 0; i < 256; i++) {
        double t = i / 256d;
        double[] p = {
          corners[side][0] * (1 - t) + corners[(side + 1) % 4][0] * t,
          corners[side][1] * (1 - t) + corners[(side + 1) % 4][1] * t
        };
        source.gridToWorld().transform(p, 0, p, 0, 1);
        forward.transform(p, 0, p, 0, 1);
        if (target instanceof GeographicCRS && (p[0] < -180 || p[0] > 180))
          throw new IllegalArgumentException(
              "Raster crosses the longitude discontinuity; split it upstream");
        if (!Double.isFinite(p[0]) || !Double.isFinite(p[1]))
          throw new IllegalArgumentException("Source boundary cannot be transformed to target CRS");
        edge[side * 256 + i] = new Coordinate(p[0], p[1]);
        if (target instanceof GeographicCRS
            && side * 256 + i > 0
            && Math.abs(p[0] - edge[side * 256 + i - 1].x) > 180)
          throw new IllegalArgumentException(
              "Raster crosses the longitude discontinuity; split it upstream");
      }
    edge[edge.length - 1] = edge[0].copy();
    var factory = new GeometryFactory();
    var footprint = factory.createPolygon(edge);
    if (!footprint.isValid())
      throw new IllegalArgumentException("Transformed raster boundary is ambiguous");
    Envelope extent = footprint.getEnvelopeInternal();
    if (request.extent() != null) {
      var e = request.extent();
      extent = new Envelope(e.minX(), e.maxX(), e.minY(), e.maxY());
      if (!footprint.intersects(factory.toGeometry(extent)))
        throw new IllegalArgumentException("Target extent does not overlap the raster");
    }
    double left = floor(extent.getMinX() / request.resolutionX());
    double right = ceil(extent.getMaxX() / request.resolutionX());
    double bottom = floor(extent.getMinY() / request.resolutionY());
    double top = ceil(extent.getMaxY() / request.resolutionY());
    double w = right - left, h = top - bottom;
    if (!(w >= 1 && h >= 1 && w <= Integer.MAX_VALUE - 1024 && h <= Integer.MAX_VALUE - 1024))
      throw new IllegalArgumentException("Target grid dimensions are empty or too large");
    var affine =
        new AffineTransform2D(
            request.resolutionX(),
            0,
            0,
            -request.resolutionY(),
            (left + .5) * request.resolutionX(),
            (top - .5) * request.resolutionY());
    return new RasterTargetGrid((int) w, (int) h, target, affine, inverse);
  }

  // Avoid adding a cell for floating-point noise at an otherwise exact grid boundary.
  private static double snap(double v) {
    double nearest = Math.rint(v);
    return Math.abs(v - nearest) <= 8 * Math.ulp(v) ? nearest : v;
  }

  private static double floor(double v) {
    return Math.floor(snap(v));
  }

  private static double ceil(double v) {
    return Math.ceil(snap(v));
  }
}
