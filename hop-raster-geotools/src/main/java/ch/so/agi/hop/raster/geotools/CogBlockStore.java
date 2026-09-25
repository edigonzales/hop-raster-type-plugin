package ch.so.agi.hop.raster.geotools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Temporary store of compressed overview tiles. Blocks are appended in tile order; the in-memory
 * index keeps only offsets and lengths, never pixel data. The backing file is deleted on close.
 */
final class CogBlockStore implements AutoCloseable {
  private final Path file;
  private final FileChannel channel;
  private final long[] offsets;
  private final int[] lengths;
  private int count;
  private long position;
  private boolean closed;

  private CogBlockStore(Path file, FileChannel channel, int tiles) {
    this.file = file;
    this.channel = channel;
    this.offsets = new long[tiles];
    this.lengths = new int[tiles];
  }

  static CogBlockStore create(Path directory, String prefix, int tiles) throws IOException {
    Path file = Files.createTempFile(directory, prefix, ".tmp");
    FileChannel channel =
        FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    return new CogBlockStore(file, channel, tiles);
  }

  int size() {
    return count;
  }

  Path file() {
    return file;
  }

  void append(byte[] data, int length) throws IOException {
    if (count >= offsets.length) throw new IllegalStateException("Overview store is full");
    offsets[count] = position;
    lengths[count] = length;
    ByteBuffer buffer = ByteBuffer.wrap(data, 0, length);
    while (buffer.hasRemaining()) position += channel.write(buffer, position);
    count++;
  }

  int read(int tile, byte[] target) throws IOException {
    int length = lengths[tile];
    if (target.length < length) throw new IllegalArgumentException("Read buffer too small");
    ByteBuffer buffer = ByteBuffer.wrap(target, 0, length);
    long offset = offsets[tile];
    while (buffer.hasRemaining()) offset += channel.read(buffer, offset);
    return length;
  }

  int length(int tile) {
    return lengths[tile];
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    try {
      channel.close();
    } catch (IOException ignored) {
    }
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
    }
  }
}
