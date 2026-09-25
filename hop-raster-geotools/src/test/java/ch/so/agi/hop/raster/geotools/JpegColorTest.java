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
  void geoTiffHonorsJpegQualityForMainImageAndOverviews() throws Exception {
    int size = 600;
    var original = new BufferedImage(size, size, BufferedImage.TYPE_3BYTE_BGR);
    var random = new java.util.Random(17);
    for (int y = 0; y < size; y++)
      for (int x = 0; x < size; x++) {
        int v = random.nextInt(256);
        original.setRGB(x, y, v * 0x010101);
      }
    var params = new GeoTiffWriteParams();
    params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
    params.setCompressionType("Deflate");
    Path input = dir.resolve("lossless.tif");
    GeoTiffOutput.write(
        input,
        original,
        CRS.decode("EPSG:2056", true),
        new AffineTransform(1, 0, 0, -1, 2600000.5, 1200600.5),
        new double[] {1, 1, 1},
        new double[] {0, 0, 0},
        null,
        new RasterColorInfo(RasterColorInfo.Kind.RGB, -1, false, null),
        params);
    double[][] errors = new double[2][2];
    long[] sizes = new long[2];
    for (int q = 0; q < 2; q++) {
      Path output = dir.resolve("quality-" + q + ".tif");
      try (var backend = new GeoToolsRasterBackend()) {
        backend.write(
            backend.describe(input.toString()),
            output,
            false,
            () -> false,
            new ch.so.agi.hop.raster.RasterWriteOptions(
                ch.so.agi.hop.raster.RasterWriteOptions.Format.GEOTIFF,
                ch.so.agi.hop.raster.RasterWriteOptions.Overviews.AUTO,
                ch.so.agi.hop.raster.RasterWriteOptions.Resampling.AVERAGE,
                512,
                "JPEG",
                q == 0 ? 20 : 95),
            ignored -> {});
      }
      sizes[q] = Files.size(output);
      try (var stream = ImageIO.createImageInputStream(output.toFile())) {
        var reader = ImageIO.getImageReaders(stream).next();
        try {
          reader.setInput(stream);
          assertThat(reader.getNumImages(true)).isEqualTo(2);
          for (int level = 0; level < 2; level++) {
            var decoded = reader.read(level);
            int factor = 1 << level;
            for (int y = 0; y < decoded.getHeight(); y++)
              for (int x = 0; x < decoded.getWidth(); x++) {
                double expected = 0;
                for (int dy = 0; dy < factor; dy++)
                  for (int dx = 0; dx < factor; dx++)
                    expected += original.getRGB(x * factor + dx, y * factor + dy) & 255;
                expected = Math.rint(expected / (factor * factor));
                double delta = (decoded.getRGB(x, y) & 255) - expected;
                errors[q][level] += delta * delta;
              }
          }
        } finally {
          reader.dispose();
        }
      }
    }
    assertThat(sizes[1]).isGreaterThan(sizes[0]);
    assertThat(errors[1][0]).isLessThan(errors[0][0]);
    assertThat(errors[1][1]).isLessThan(errors[0][1]);
  }

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
