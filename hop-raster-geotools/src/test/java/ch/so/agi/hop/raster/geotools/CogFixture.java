package ch.so.agi.hop.raster.geotools;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.DeflaterOutputStream;

/**
 * Independent BigTIFF fixture: metadata first, overview first, then four original-resolution tiles.
 */
final class CogFixture {
  private record Tag(int type, long count, byte[] bytes) {}

  static Path write(Path output) throws Exception {
    List<byte[]> tiles = new ArrayList<>();
    java.util.Random random = new java.util.Random(123);
    for (int tile = 0; tile < 5; tile++) {
      ByteBuffer raw = ByteBuffer.allocate(512 * 512 * 4).order(ByteOrder.LITTLE_ENDIAN);
      for (int y = 0; y < 512; y++)
        for (int x = 0; x < 512; x++) {
          float v = tile == 0 ? -12345 : random.nextFloat() * 1000;
          if (tile == 1 && y < 3 && x < 4) v = 1 + y * 4 + x;
          if (tile == 1 && y == 0 && x == 4) v = -9999;
          if (tile == 1 && y == 0 && x == 5) v = 0;
          raw.putFloat(v);
        }
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (var deflate = new DeflaterOutputStream(bytes)) {
        deflate.write(raw.array());
      }
      tiles.add(bytes.toByteArray());
    }
    long[] offsets = new long[5], counts = new long[5];
    long position = 4096;
    for (int i = 0; i < 5; i++) {
      offsets[i] = position;
      counts[i] = tiles.get(i).length;
      position += counts[i];
    }
    ByteBuffer header = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
    header
        .put((byte) 'I')
        .put((byte) 'I')
        .putShort((short) 43)
        .putShort((short) 8)
        .putShort((short) 0)
        .putLong(16);
    directory(
        header,
        16,
        1024,
        1024,
        java.util.Arrays.copyOfRange(offsets, 1, 5),
        java.util.Arrays.copyOfRange(counts, 1, 5),
        false);
    directory(header, 1024, 0, 512, new long[] {offsets[0]}, new long[] {counts[0]}, true);
    try (var stream = Files.newOutputStream(output)) {
      stream.write(header.array());
      for (byte[] tile : tiles) stream.write(tile);
    }
    return output;
  }

  private static void directory(
      ByteBuffer b,
      int location,
      int next,
      int size,
      long[] offsets,
      long[] counts,
      boolean overview) {
    TreeMap<Integer, Tag> tags = new TreeMap<>();
    if (overview) tags.put(254, ints(1));
    tags.put(256, ints(size));
    tags.put(257, ints(size));
    tags.put(258, shorts(32));
    tags.put(259, shorts(8));
    tags.put(262, shorts(1));
    tags.put(277, shorts(1));
    tags.put(284, shorts(1));
    tags.put(317, shorts(1));
    tags.put(322, ints(512));
    tags.put(323, ints(512));
    tags.put(324, longs(offsets));
    tags.put(325, longs(counts));
    tags.put(339, shorts(3));
    if (!overview) {
      tags.put(33550, doubles(.25, .25, 0));
      tags.put(33922, doubles(0, 0, 0, 2600000, 1200256, 0));
      tags.put(34735, shorts(1, 1, 0, 3, 1024, 0, 1, 1, 1025, 0, 1, 1, 3072, 0, 1, 2056));
    }
    tags.put(42113, new Tag(2, 6, new byte[] {'-', '9', '9', '9', '9', 0}));
    b.position(location);
    b.putLong(tags.size());
    int external = location + 8 + tags.size() * 20 + 8;
    for (var entry : tags.entrySet()) {
      Tag t = entry.getValue();
      b.putShort(entry.getKey().shortValue()).putShort((short) t.type).putLong(t.count);
      if (t.bytes.length <= 8) {
        b.put(t.bytes);
        for (int i = t.bytes.length; i < 8; i++) b.put((byte) 0);
      } else {
        b.putLong(external);
        int cursor = b.position();
        b.position(external);
        b.put(t.bytes);
        external += t.bytes.length;
        b.position(cursor);
      }
    }
    b.putLong(next);
  }

  private static Tag shorts(int... v) {
    ByteBuffer b = ByteBuffer.allocate(v.length * 2).order(ByteOrder.LITTLE_ENDIAN);
    for (int i : v) b.putShort((short) i);
    return new Tag(3, v.length, b.array());
  }

  private static Tag ints(int... v) {
    ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (int i : v) b.putInt(i);
    return new Tag(4, v.length, b.array());
  }

  private static Tag longs(long... v) {
    ByteBuffer b = ByteBuffer.allocate(v.length * 8).order(ByteOrder.LITTLE_ENDIAN);
    for (long i : v) b.putLong(i);
    return new Tag(16, v.length, b.array());
  }

  private static Tag doubles(double... v) {
    ByteBuffer b = ByteBuffer.allocate(v.length * 8).order(ByteOrder.LITTLE_ENDIAN);
    for (double i : v) b.putDouble(i);
    return new Tag(12, v.length, b.array());
  }
}
