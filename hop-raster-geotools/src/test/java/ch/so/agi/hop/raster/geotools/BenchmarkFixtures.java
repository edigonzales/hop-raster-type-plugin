package ch.so.agi.hop.raster.geotools;

import java.awt.*;
import java.awt.image.*;
import java.nio.file.*;
import javax.imageio.ImageWriteParam;
import org.eclipse.imagen.*;
import org.geotools.gce.geotiff.GeoTiffWriteParams;
import org.geotools.referencing.CRS;

/** Deterministic tiled files generated without allocating the complete image. */
public final class BenchmarkFixtures {
  public static void main(String[] args) throws Exception {
    Path dir = Path.of(args[0]);
    Files.createDirectories(dir);
    int size = args.length > 1 ? Integer.parseInt(args[1]) : 2048;
    String[] kinds =
        args.length > 2 && args[2].equals("variants")
            ? new String[] {"rgba", "palette", "numeric4"}
            : new String[] {"dem", "rgb"};
    for (String kind : kinds) {
      boolean rgb = kind.equals("rgb") || kind.equals("rgba");
      boolean palette = kind.equals("palette");
      int bands = kind.equals("rgba") || kind.equals("numeric4") ? 4 : rgb ? 3 : 1;
      int type = rgb || palette ? DataBuffer.TYPE_BYTE : DataBuffer.TYPE_FLOAT;
      char[] map = new char[768];
      for (int i = 0; i < 256; i++) {
        map[i] = (char) (i * 257);
        map[256 + i] = (char) ((255 - i) * 257);
        map[512 + i] = (char) (128 * 257);
      }
      double[] scales = new double[bands], offsets = new double[bands];
      java.util.Arrays.fill(scales, 1);
      var color =
          rgb
              ? new RasterColorInfo(
                  RasterColorInfo.Kind.RGB, kind.equals("rgba") ? 3 : -1, false, null)
              : palette
                  ? new RasterColorInfo(RasterColorInfo.Kind.PALETTE, -1, false, map)
                  : RasterColorInfo.numeric();
      var image =
          new SourcelessOpImage(
              new ImageLayout()
                  .setTileWidth(512)
                  .setTileHeight(512)
                  .setColorModel(GeoTiffOutput.colorModel(type, bands, color)),
              new RenderingHints(ImageN.KEY_TILE_CACHE, ImageN.createTileCache(16L * 1024 * 1024)),
              new BandedSampleModel(type, 512, 512, bands),
              0,
              0,
              size,
              size) {
            protected void computeRect(PlanarImage[] ignored, WritableRaster out, Rectangle rect) {
              for (int y = rect.y; y < rect.y + rect.height; y++)
                for (int x = rect.x; x < rect.x + rect.width; x++)
                  for (int b = 0; b < bands; b++) {
                    double v =
                        palette
                            ? (x + y) % 255
                            : rgb
                                ? (b == 3 ? 255 : ((x * 31 + y * 17 + b * 71) & 255))
                                : ((x + y) % 113 == 0
                                    ? -9999
                                    : 100
                                        + b * 1000
                                        + Math.sin(x * .03) * 35
                                        + Math.cos(y * .04) * 29);
                    out.setSample(x, y, b, v);
                  }
            }
          };
      var options = new GeoTiffWriteParams();
      options.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
      options.setTiling(512, 512);
      options.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      options.setCompressionType("Deflate");
      options.setForceToBigTIFF(true);
      try {
        GeoTiffOutput.write(
            dir.resolve(kind + ".tif"),
            image,
            CRS.decode("EPSG:2056", true),
            new java.awt.geom.AffineTransform(.5, 0, 0, -.5, 2600000.25, 1200000 + size * .5 - .25),
            scales,
            offsets,
            rgb ? null : palette ? 255d : -9999d,
            color,
            options);
      } finally {
        image.dispose();
      }
    }
  }
}
