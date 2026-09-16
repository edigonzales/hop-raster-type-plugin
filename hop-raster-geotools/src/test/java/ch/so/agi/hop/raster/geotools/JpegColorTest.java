package ch.so.agi.hop.raster.geotools;

import static org.assertj.core.api.Assertions.*;

import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.*;
import org.geotools.gce.geotiff.GeoTiffWriteParams;
import org.geotools.referencing.CRS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JpegColorTest {
  @TempDir Path dir;

  @Test
  void jpegYCbCrIsExposedAsDecodedRgb() throws Exception {
    var image = new BufferedImage(32, 32, BufferedImage.TYPE_3BYTE_BGR);
    for (int y = 0; y < 32; y++)
      for (int x = 0; x < 32; x++) image.setRGB(x, y, ((x * 7) << 16) | ((y * 7) << 8) | 61);
    var params = new GeoTiffWriteParams();
    params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
    params.setCompressionType("JPEG");
    params.setCompressionQuality(.9f);
    Path file = dir.resolve("jpeg.tif");
    GeoTiffOutput.write(
        file,
        image,
        CRS.decode("EPSG:2056", true),
        new AffineTransform(1, 0, 0, -1, 2600000.5, 1200031.5),
        new double[] {1, 1, 1},
        new double[] {0, 0, 0},
        null,
        new RasterColorInfo(RasterColorInfo.Kind.RGB, -1, false, null),
        params);
    try (var stream = ImageIO.createImageInputStream(file.toFile())) {
      var reader = ImageIO.getImageReaders(stream).next();
      reader.setInput(stream);
      var tags =
          it.geosolutions.imageio.plugins.tiff.TIFFDirectory.createFromMetadata(
              reader.getImageMetadata(0));
      assertThat(tags.getTIFFField(262).getAsInt(0)).isEqualTo(6);
      var expected = reader.read(0);
      try (var source = new GeoTiffSource(new RasterDatasetRef(file.toString()))) {
        assertThat(source.colorInfo().kind()).isEqualTo(RasterColorInfo.Kind.RGB);
        for (int b = 0; b < 3; b++) {
          var samples = source.read(new RasterReadRequest(new Rectangle(0, 0, 32, 32), b));
          for (int y = 0; y < 32; y++)
            for (int x = 0; x < 32; x++)
              assertThat(samples.getSample(x, y, 0))
                  .isEqualTo((expected.getRGB(x, y) >> (16 - 8 * b)) & 255);
        }
      }
      reader.dispose();
    }
  }
}
