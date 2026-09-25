package ch.so.agi.hop.raster.geotools;

import it.geosolutions.imageio.plugins.tiff.TIFFDirectory;
import it.geosolutions.imageio.plugins.tiff.TIFFField;
import it.geosolutions.imageio.plugins.tiff.TIFFImageWriteParam;
import it.geosolutions.imageio.plugins.tiff.TIFFTag;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFImageWriter;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFImageWriterSpi;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFJPEGCompressor;
import java.awt.Point;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Per-tile baseline JPEG encoder with YCbCr color space for 1- and 3-band byte rasters. The
 * compressor is the same ImageIO-Ext class as in the plain GeoTIFF writer; a one-pixel public
 * write initializes the image type the compressor needs for its JPEG stream metadata.
 *
 * <p>Tiles are written as abbreviated streams whose quantization and Huffman tables live once in
 * the IFD's {@code JPEGTables} field, matching GDAL. The tables are extracted from a real stream
 * of this codec, so they always match the configured quality.
 */
final class CogJpegCodec implements CogCodec {
  private final TIFFJPEGCompressor compressor;
  private final TIFFImageWriter writer;
  private final int bands;
  private final int photometric;
  private final int[] bitsPerSample;
  private final CogTileBuffer complete = new CogTileBuffer();
  private byte[] tables;

  private CogJpegCodec(
      TIFFJPEGCompressor compressor, TIFFImageWriter writer, int bands, int photometric) {
    this.compressor = compressor;
    this.writer = writer;
    this.bands = bands;
    this.photometric = photometric;
    this.bitsPerSample = new int[bands];
    java.util.Arrays.fill(bitsPerSample, 8);
  }

  static CogJpegCodec create(int quality, int bands, int dataType, RasterColorInfo color)
      throws IOException {
    if (dataType != DataBuffer.TYPE_BYTE)
      throw new IllegalArgumentException("JPEG COG supports 8-bit rasters only");
    boolean gray = bands == 1 && color.kind() == RasterColorInfo.Kind.NUMERIC;
    boolean rgb = bands == 3 && color.kind() == RasterColorInfo.Kind.RGB;
    if (!gray && !rgb)
      throw new IllegalArgumentException(
          "JPEG COG supports single-band numeric or three-band RGB byte rasters only"
              + (color.alphaBand() >= 0 ? " (alpha channels need a mask and are not supported)" : ""));
    TIFFImageWriteParam param = new TIFFImageWriteParam(Locale.ROOT);
    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
    param.setCompressionType("JPEG");
    param.setCompressionQuality(quality / 100f);
    TIFFJPEGCompressor compressor = new TIFFJPEGCompressor(param);
    TIFFImageWriter writer = (TIFFImageWriter) new TIFFImageWriterSpi().createWriterInstance();
    try (var scratch = new MemoryCacheImageOutputStream(new ByteArrayOutputStream())) {
      writer.setOutput(scratch);
      // The compressor reads the writer's image type for its JPEG stream metadata; the public
      // write call is the only non-reflective way to populate it.
      writer.write(dummyImage(bands));
    }
    compressor.setWriter(writer);
    return new CogJpegCodec(compressor, writer, bands, rgb ? 6 : 1);
  }

  private static BufferedImage dummyImage(int bands) {
    if (bands == 1) return new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY);
    var colorModel =
        new ComponentColorModel(
            ColorSpace.getInstance(ColorSpace.CS_sRGB),
            false,
            false,
            Transparency.OPAQUE,
            DataBuffer.TYPE_BYTE);
    return new BufferedImage(
        colorModel,
        Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, 1, 1, 3, new Point()),
        false,
        null);
  }

  @Override
  public int tag() {
    return 7;
  }

  @Override
  public String name() {
    return "JPEG";
  }

  @Override
  public boolean lossless() {
    return false;
  }

  /**
   * Sets the YCbCr (or grayscale) interpretation, lets the compressor add its subsampling,
   * positioning and reference-black-white fields and extracts the shared JPEG tables for the
   * requested quality. Returns the updated directory.
   */
  TIFFDirectory prepareDirectory(TIFFDirectory directory) throws Exception {
    directory.addTIFFField(
        new TIFFField(
            new TIFFTag("PhotometricInterpretation", 262, 1 << TIFFTag.TIFF_SHORT),
            TIFFTag.TIFF_SHORT,
            1,
            new char[] {(char) photometric}));
    compressor.setMetadata(directory.getAsMetadata());
    extractTables();
    return TIFFDirectory.createFromMetadata(directory.getAsMetadata());
  }

  /** The {@code JPEGTables} field for the main and overview directories. */
  byte[] tables() {
    if (tables == null) throw new IllegalStateException("JPEG tables not prepared");
    return tables;
  }

  private void extractTables() throws IOException {
    int block = 16;
    byte[] probe = new byte[block * block * bands];
    complete.reset();
    int length;
    try (var stream = complete.open()) {
      length = encodeComplete(stream, probe, block, block, block * bands);
    }
    tables = CogJpegTables.extract(complete.data(), length);
  }

  @Override
  public int encode(ImageOutputStream stream, byte[] raw, int width, int height, int stride)
      throws IOException {
    complete.reset();
    int length;
    try (var out = complete.open()) {
      length = encodeComplete(out, raw, width, height, stride);
    }
    int abbreviated = CogJpegTables.abbreviate(complete.data(), length, complete.data());
    stream.write(complete.data(), 0, abbreviated);
    return abbreviated;
  }

  private int encodeComplete(
      ImageOutputStream stream, byte[] raw, int width, int height, int stride)
      throws IOException {
    compressor.setStream(stream);
    return compressor.encode(raw, 0, width, height, bitsPerSample, stride);
  }

  @Override
  public void close() {
    compressor.dispose();
    writer.dispose();
  }
}
