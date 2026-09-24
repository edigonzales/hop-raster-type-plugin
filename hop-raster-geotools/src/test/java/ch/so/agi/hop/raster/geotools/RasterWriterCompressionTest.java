package ch.so.agi.hop.raster.geotools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.geosolutions.imageio.plugins.tiff.BaselineTIFFTagSet;
import it.geosolutions.imageio.plugins.tiff.TIFFDirectory;
import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RasterWriterCompressionTest {
  @TempDir Path dir;

  @Test
  void exposesEveryBundledTiffCompressionMode() {
    assertThat(GeoToolsRasterBackend.compressionTypes())
        .containsExactly(
            "CCITT RLE",
            "CCITT T.4",
            "CCITT T.6",
            "LZW",
            "JPEG",
            "ZLib",
            "PackBits",
            "Deflate",
            "EXIF JPEG",
            "ZSTD");
  }

  @Test
  void writesSelectedCompressionAndReportsMonotonicProgress() throws Exception {
    Path input = RasterCoreTest.fixture(dir.resolve("input.tif"));
    try (var backend = new GeoToolsRasterBackend()) {
      var dataset = backend.describe(input.toString());
      for (var selection :
          List.of(
              new Selection("None", 1),
              new Selection("Deflate", 32946),
              new Selection("LZW", 5))) {
        Path output = dir.resolve(selection.name().toLowerCase() + ".tif");
        var progress = new ArrayList<Integer>();
        backend.write(dataset, output, false, () -> false, selection.name(), progress::add);

        assertThat(compressionTag(output)).isEqualTo(selection.tag());
        assertThat(progress).isNotEmpty().contains(100);
        assertThat(progress).isSorted();
        try (var written = new GeoTiffSource(new RasterDatasetRef(output.toString()))) {
          var pixels = written.read(new RasterReadRequest(new Rectangle(0, 0, 4, 3), 0));
          assertThat(pixels.getSampleDouble(3, 2, 0)).isEqualTo(12);
        }
      }

      Path defaultOutput = dir.resolve("default.tif");
      backend.write(dataset, defaultOutput, false, () -> false);
      assertThat(compressionTag(defaultOutput)).isEqualTo(32946);
    }
  }

  @Test
  void unsupportedCompressionCleansTemporaryOutput() throws Exception {
    Path input = RasterCoreTest.fixture(dir.resolve("input.tif"));
    Path output = dir.resolve("unsupported.tif");
    try (var backend = new GeoToolsRasterBackend()) {
      var dataset = backend.describe(input.toString());
      assertThatThrownBy(
              () ->
                  backend.write(
                      dataset, output, false, () -> false, "not-a-codec", ignored -> {}))
          .isInstanceOf(Exception.class);
    }
    assertThat(output).doesNotExist();
    try (var paths = Files.list(dir)) {
      assertThat(paths.map(path -> path.getFileName().toString()).toList())
          .noneMatch(name -> name.startsWith(".hop-raster-"));
    }
  }

  private static int compressionTag(Path file) throws Exception {
    var readers = ImageIO.getImageReadersByFormatName("TIFF");
    assertThat(readers.hasNext()).isTrue();
    ImageReader reader = readers.next();
    try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
      reader.setInput(input);
      var field =
          TIFFDirectory.createFromMetadata(reader.getImageMetadata(0))
              .getTIFFField(BaselineTIFFTagSet.TAG_COMPRESSION);
      return field.getAsInt(0);
    } finally {
      reader.dispose();
    }
  }

  private record Selection(String name, int tag) {}
}
