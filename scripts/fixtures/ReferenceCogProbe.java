import ch.so.agi.hop.raster.core.*;
import java.awt.geom.*;
import java.nio.file.*;
import org.locationtech.jts.geom.*;

/** Compiled only against frozen baseline JARs, never candidate algorithm classes. */
public final class ReferenceCogProbe {
  public static void main(String[] args) throws Exception {
    Path out = Path.of(args[1]);
    Files.createDirectories(out);
    int sx = Integer.parseInt(args[2]), sy = Integer.parseInt(args[3]);
    try (var source = new GeoTiffSource(new RasterDatasetRef(args[0]))) {
      for (int size : new int[] {128, 1024}) {
        int x = Math.min(sx, source.bounds().width - size),
            y = Math.min(sy, source.bounds().height - size);
        Coordinate[] points = new Coordinate[5];
        int[][] corners = {{x, y}, {x + size, y}, {x + size, y + size}, {x, y + size}, {x, y}};
        var grid = (AffineTransform) source.gridToWorld();
        for (int i = 0; i < 5; i++) {
          var point =
              grid.transform(new Point2D.Double(corners[i][0] - .5, corners[i][1] - .5), null);
          points[i] = new Coordinate(point.getX(), point.getY());
        }
        var polygon = new GeometryFactory().createPolygon(points);
        Path clip = out.resolve("clip-" + size + ".tif");
        RasterClip.write(
            source, polygon, false, 0, source.bands() > 1 ? 0d : null, clip, true, () -> false);
        try (var clipped = new GeoTiffSource(new RasterDatasetRef(clip.toString()))) {
          RasterReproject.write(
              clipped,
              new RasterReprojectRequest(
                  null,
                  Math.hypot(grid.getScaleX(), grid.getShearY()) * 2,
                  Math.hypot(grid.getShearX(), grid.getScaleY()) * 2,
                  null,
                  RasterReprojectRequest.Interpolation.BILINEAR,
                  RasterReprojectRequest.OutputType.AUTO,
                  null,
                  null,
                  out.resolve("band1-" + size + ".tif"),
                  true),
              () -> false);
          System.out.println("STATS " + ZonalStatistics.compute(clipped, polygon, 0, null));
        }
      }
    }
    System.out.println("REFERENCE_PASS");
  }
}
