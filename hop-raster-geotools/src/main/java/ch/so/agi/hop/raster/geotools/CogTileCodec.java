package ch.so.agi.hop.raster.geotools;

import io.airlift.compress.zstd.ZstdDecompressor;
import it.geosolutions.imageio.compression.CompressionFinder;
import it.geosolutions.imageio.plugins.tiff.TIFFCompressor;
import it.geosolutions.imageio.plugins.tiff.TIFFImageWriteParam;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFDeflateCompressor;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFLZWCompressor;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFLZWDecompressor;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFNullCompressor;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFPackBitsCompressor;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFPackBitsDecompressor;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFZLibCompressor;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFZSTDCompressor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Per-tile TIFF compression for the lossless codecs of the bundled ImageIO-Ext stack. The COG
 * writer drives the same codec classes as the plain GeoTIFF writer, so tile bytes and compression
 * tags stay compatible. JPEG and CCITT remain exclusive to plain GeoTIFF output.
 */
final class CogTileCodec implements CogCodec {
  private final String name;
  private final int tag;
  private final int bands;
  private final int[] bitsPerSample;
  private final TIFFCompressor compressor;
  private final TIFFLZWDecompressor lzw;
  private final TIFFPackBitsDecompressor packBits;
  private final ZstdDecompressor zstd;
  private final Inflater inflater = new Inflater();

  private CogTileCodec(
      String name,
      int tag,
      int bands,
      int[] bitsPerSample,
      int[] sampleFormat,
      TIFFCompressor compressor)
      throws java.io.IOException {
    this.name = name;
    this.tag = tag;
    this.bands = bands;
    this.bitsPerSample = bitsPerSample.clone();
    this.compressor = compressor;
    this.lzw = "LZW".equals(name) ? new TIFFLZWDecompressor(1) : null;
    this.packBits = "PackBits".equals(name) ? new TIFFPackBitsDecompressor() : null;
    this.zstd = "ZSTD".equals(name) ? new ZstdDecompressor() : null;
    if (lzw != null) {
      // The decoder reaches for a stream only to read the byte order of the sample words.
      lzw.setStream(new MemoryCacheImageOutputStream(new ByteArrayOutputStream()));
      lzw.setBitsPerSample(bitsPerSample.clone());
      lzw.setSampleFormat(sampleFormat.clone());
      lzw.setSamplesPerPixel(bands);
      lzw.setPlanar(false);
    }
  }

  static CogTileCodec create(
      String compression, int bands, int[] bitsPerSample, int[] sampleFormat)
      throws java.io.IOException {
    CompressionFinder.scanForPlugins();
    String name = compression == null || compression.isBlank() ? "Deflate" : compression;
    TIFFImageWriteParam param = new TIFFImageWriteParam(Locale.ROOT);
    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
    return switch (name) {
      case "None" ->
          new CogTileCodec(
              name, 1, bands, bitsPerSample, sampleFormat, new TIFFNullCompressor());
      case "Deflate" -> {
        param.setCompressionType("Deflate");
        yield new CogTileCodec(
            name, 32946, bands, bitsPerSample, sampleFormat, new TIFFDeflateCompressor(param, 1));
      }
      case "ZLib" -> {
        param.setCompressionType("ZLib");
        yield new CogTileCodec(
            name, 8, bands, bitsPerSample, sampleFormat, new TIFFZLibCompressor(param, 1));
      }
      case "LZW" ->
          new CogTileCodec(
              name, 5, bands, bitsPerSample, sampleFormat, new TIFFLZWCompressor(1));
      case "ZSTD" -> {
        param.setCompressionType("ZSTD");
        yield new CogTileCodec(
            name, 50000, bands, bitsPerSample, sampleFormat, new TIFFZSTDCompressor(param, 1));
      }
      case "PackBits" ->
          new CogTileCodec(
              name, 32773, bands, bitsPerSample, sampleFormat, new TIFFPackBitsCompressor());
      default ->
          throw new IllegalArgumentException(
              "COG output supports None, Deflate, ZLib, LZW, ZSTD and PackBits, not " + name);
    };
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public boolean lossless() {
    return true;
  }

  @Override
  public int tag() {
    return tag;
  }

  /** Compresses one padded tile and returns the byte count. */
  @Override
  public int encode(ImageOutputStream stream, byte[] raw, int width, int height, int stride)
      throws IOException {
    compressor.setStream(stream);
    return compressor.encode(raw, 0, width, height, bitsPerSample, stride);
  }

  /** Decompresses one tile into {@code raw}; the tile must contain {@code raw.length} bytes. */
  void decode(byte[] compressed, int offset, int length, byte[] raw) throws IOException {
    if (compressor instanceof TIFFNullCompressor) {
      System.arraycopy(compressed, offset, raw, 0, raw.length);
      return;
    }
    if (zstd != null) {
      int written = zstd.decompress(compressed, offset, length, raw, 0, raw.length);
      if (written != raw.length) throw new IOException("ZSTD tile size mismatch");
      return;
    }
    if (packBits != null) {
      int written = packBits.decode(compressed, offset, raw, 0);
      if (written != raw.length) throw new IOException("PackBits tile size mismatch");
      return;
    }
    if (lzw != null) {
      int written = lzw.decode(compressed, offset, raw, 0, raw.length);
      if (written != raw.length) throw new IOException("LZW tile size mismatch");
      return;
    }
    inflater.reset();
    inflater.setInput(compressed, offset, length);
    int written = 0;
    try {
      while (!inflater.finished() && written < raw.length) {
        int n = inflater.inflate(raw, written, raw.length - written);
        if (n == 0 && inflater.needsInput()) break;
        written += n;
      }
    } catch (DataFormatException e) {
      throw new IOException("Invalid " + name + " tile", e);
    }
    if (written != raw.length) throw new IOException(name + " tile size mismatch");
  }

  @Override
  public void close() {
    compressor.dispose();
    if (lzw != null) lzw.dispose();
    if (packBits != null) packBits.dispose();
  }
}
