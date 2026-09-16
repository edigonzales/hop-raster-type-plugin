package ch.so.agi.hop.raster.geotools;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;

/** Hand-authored TIFF, so input color/alpha tests do not depend on our output writer. */
final class RasterColorFixture {
  private record Tag(int type, int count, byte[] bytes) {}

  private RasterColorFixture() {}

  static Path write(Path file, int photo, int alpha, int[][] pixels) throws Exception {
    return write(file, photo, alpha, pixels, null);
  }

  static Path write(Path file, int photo, int alpha, int[][] pixels, Integer noData)
      throws Exception {
    int bands = pixels.length, size = pixels[0].length;
    var tags = new TreeMap<Integer, Tag>();
    if (noData != null) {
      byte[] text = (noData.toString() + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
      tags.put(42113, new Tag(2, text.length, text));
    }
    tags.put(256, ints(size));
    tags.put(257, ints(1));
    int[] bits = new int[bands];
    java.util.Arrays.fill(bits, 8);
    tags.put(258, shorts(bits));
    tags.put(259, shorts(1));
    tags.put(262, shorts(photo));
    tags.put(273, ints(4096));
    tags.put(277, shorts(bands));
    tags.put(278, ints(1));
    tags.put(279, ints(size * bands));
    tags.put(284, shorts(1));
    if (alpha > 0) tags.put(338, shorts(alpha));
    tags.put(33550, doubles(1, 1, 0));
    tags.put(33922, doubles(0, 0, 0, 0, 1, 0));
    tags.put(34735, shorts(1, 1, 0, 3, 1024, 0, 1, 1, 1025, 0, 1, 1, 3072, 0, 1, 3857));
    ByteBuffer out = ByteBuffer.allocate(4096 + size * bands).order(ByteOrder.LITTLE_ENDIAN);
    out.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
    out.putShort((short) tags.size());
    int external = 8 + 2 + tags.size() * 12 + 4;
    for (var entry : tags.entrySet()) {
      Tag tag = entry.getValue();
      out.putShort(entry.getKey().shortValue()).putShort((short) tag.type).putInt(tag.count);
      if (tag.bytes.length <= 4) {
        out.put(tag.bytes);
        for (int i = tag.bytes.length; i < 4; i++) out.put((byte) 0);
      } else {
        out.putInt(external);
        int cursor = out.position();
        out.position(external);
        out.put(tag.bytes);
        external += tag.bytes.length;
        out.position(cursor);
      }
    }
    out.putInt(0);
    out.position(4096);
    for (int x = 0; x < size; x++) for (int b = 0; b < bands; b++) out.put((byte) pixels[b][x]);
    Files.write(file, out.array());
    return file;
  }

  private static Tag shorts(int... values) {
    var b = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
    for (int v : values) b.putShort((short) v);
    return new Tag(3, values.length, b.array());
  }

  private static Tag ints(int... values) {
    var b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (int v : values) b.putInt(v);
    return new Tag(4, values.length, b.array());
  }

  private static Tag doubles(double... values) {
    var b = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
    for (double v : values) b.putDouble(v);
    return new Tag(12, values.length, b.array());
  }
}
