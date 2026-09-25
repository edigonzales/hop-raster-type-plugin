package ch.so.agi.hop.raster.geotools;

import java.io.IOException;

/**
 * Baseline JPEG marker handling: separates the shared tables (DQT/DHT) from a complete stream so
 * the COG can store them once in the {@code JPEGTables} field and keep abbreviated tile streams,
 * like GDAL. The tables always match the encoder quality because they are extracted from a real
 * stream of the same codec.
 */
final class CogJpegTables {
  private CogJpegTables() {}

  /** Tables-only datastream: SOI, quantization and Huffman tables, EOI. */
  static byte[] extract(byte[] jpeg, int length) {
    java.io.ByteArrayOutputStream tables = new java.io.ByteArrayOutputStream();
    tables.write(0xff);
    tables.write(0xd8);
    int position = 2;
    while (position + 4 <= length) {
      if ((jpeg[position] & 0xff) != 0xff) break;
      int marker = jpeg[position + 1] & 0xff;
      if (marker == 0xda || marker == 0xd9) break;
      if (marker == 0xd8) {
        position += 2;
        continue;
      }
      int segmentLength = ((jpeg[position + 2] & 0xff) << 8) | (jpeg[position + 3] & 0xff);
      if (segmentLength < 2 || position + 2 + segmentLength > length) break;
      if (marker == 0xdb || marker == 0xc4)
        tables.write(jpeg, position, 2 + segmentLength);
      position += 2 + segmentLength;
    }
    tables.write(0xff);
    tables.write(0xd9);
    return tables.toByteArray();
  }

  /** Abbreviated stream: everything except DQT/DHT, copied verbatim from SOI to EOI. */
  static int abbreviate(byte[] jpeg, int length, byte[] target) throws IOException {
    int position = 2;
    int written = 0;
    target[written++] = (byte) 0xff;
    target[written++] = (byte) 0xd8;
    while (position + 4 <= length) {
      if ((jpeg[position] & 0xff) != 0xff) break;
      int marker = jpeg[position + 1] & 0xff;
      if (marker == 0xd8) {
        position += 2;
        continue;
      }
      if (marker == 0xda) {
        int segmentLength = ((jpeg[position + 2] & 0xff) << 8) | (jpeg[position + 3] & 0xff);
        if (segmentLength < 2 || position + 2 + segmentLength > length)
          throw new IOException("Truncated JPEG scan header");
        int rest = length - position;
        System.arraycopy(jpeg, position, target, written, rest);
        return written + rest;
      }
      if (marker == 0xd9) break;
      int segmentLength = ((jpeg[position + 2] & 0xff) << 8) | (jpeg[position + 3] & 0xff);
      if (segmentLength < 2 || position + 2 + segmentLength > length)
        throw new IOException("Truncated JPEG segment");
      if (marker != 0xdb && marker != 0xc4) {
        System.arraycopy(jpeg, position, target, written, 2 + segmentLength);
        written += 2 + segmentLength;
      }
      position += 2 + segmentLength;
    }
    throw new IOException("JPEG stream without scan data");
  }
}
