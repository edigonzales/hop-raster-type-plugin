package ch.so.agi.hop.raster.geotools;

import java.io.OutputStream;
import java.util.Arrays;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Reusable sink for one compressed tile; keeps at most a few megabytes in memory. */
final class CogTileBuffer {
  private byte[] data = new byte[1 << 16];
  private int size;
  private final OutputStream sink =
      new OutputStream() {
        @Override
        public void write(int value) {
          ensure(1);
          data[size++] = (byte) value;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
          ensure(length);
          System.arraycopy(bytes, offset, data, size, length);
          size += length;
        }

        @Override
        public void close() {}
      };

  void reset() {
    size = 0;
  }

  ImageOutputStream open() {
    return new MemoryCacheImageOutputStream(sink);
  }

  byte[] data() {
    return data;
  }

  int size() {
    return size;
  }

  private void ensure(int extra) {
    if (size + extra > data.length)
      data = Arrays.copyOf(data, Math.max(data.length * 2, size + extra));
  }
}
