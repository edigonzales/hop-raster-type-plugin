package ch.so.agi.hop.raster.geotools;

import ch.so.agi.hop.raster.*;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

/** Large derived output, with independent nearest-neighbour checks at tile boundaries. */
public final class LargeChainProbe {
  public static void main(String[] args) throws Exception {
    try (var backend = new GeoToolsRasterBackend()) {
      var input = backend.describe(args[0]);
      var grid = input.result().grid();
      int size = grid.width() / 2, start = grid.width() / 4;
      var clipped =
          backend.derive(
              input,
              new RasterOperation.Clip(
                  "large-clip",
                  AcceptanceProbe.region(grid, start, start, size).toText(),
                  false,
                  IntStream.range(0, input.result().bands().size()).boxed().toList(),
                  null));
      var warped =
          backend.derive(
              clipped,
              new RasterOperation.Reproject(
                  "large-warp", "EPSG:2056", 1, 1, List.of(), "NEAREST", "AUTO", null, null));
      backend.write(warped, Path.of(args[1]), true, () -> false);
      AcceptanceProbe.resources(backend, 0);
      try (var actual = new GeoTiffSource(new RasterDatasetRef(args[1]));
          var source = new GeoTiffSource(new RasterDatasetRef(args[0]))) {
        if (actual.bounds().width != size / 2 || actual.bounds().height != size / 2)
          throw new AssertionError("Unexpected output grid");
        int[] points = {0, 1, 255, 256, 511, 512, 513, size / 4, size / 2 - 1};
        for (int y : points)
          for (int x : points) {
            if (x >= size / 2 || y >= size / 2) continue;
            double[] coordinate = {x, y};
            actual.gridToWorld().transform(coordinate, 0, coordinate, 0, 1);
            source.gridToWorld().inverse().transform(coordinate, 0, coordinate, 0, 1);
            int sx = (int) Math.floor(coordinate[0] + .5);
            int sy = (int) Math.floor(coordinate[1] + .5);
            for (int band = 0; band < source.bands(); band++) {
              double expected =
                  source
                      .read(new RasterReadRequest(new Rectangle(sx, sy, 1, 1), band))
                      .getSampleDouble(sx, sy, 0);
              double value =
                  actual
                      .read(new RasterReadRequest(new Rectangle(x, y, 1, 1), band))
                      .getSampleDouble(x, y, 0);
              if (expected != value)
                throw new AssertionError("Nearest sample mismatch at " + x + "," + y);
            }
          }
      }
      backend.close();
      AcceptanceProbe.resources(backend, 0);
      System.out.println("LARGE_CHAIN_PASS size=" + size);
    }
  }
}
