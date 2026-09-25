package ch.so.agi.hop.raster.geotools;

import it.geosolutions.imageio.plugins.tiff.TIFFField;
import it.geosolutions.imageio.plugins.tiff.TIFFTag;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.imageio.stream.FileImageOutputStream;

/**
 * COG container writer: TIFF/BigTIFF header, optional GDAL ghost area, IFDs with their external
 * tag values, and tile blocks with GDAL-compatible leaders and trailers. IFDs and tag values are
 * placed before the tile data; blocks are appended in the caller's order.
 */
final class CogTiff implements AutoCloseable {
  static final int TAG_NEW_SUBFILE_TYPE = 254;
  static final int TAG_IMAGE_WIDTH = 256;
  static final int TAG_IMAGE_LENGTH = 257;
  static final int TAG_BITS_PER_SAMPLE = 258;
  static final int TAG_COMPRESSION = 259;
  static final int TAG_PHOTOMETRIC = 262;
  static final int TAG_STRIP_OFFSETS = 273;
  static final int TAG_SAMPLES_PER_PIXEL = 277;
  static final int TAG_ROWS_PER_STRIP = 278;
  static final int TAG_STRIP_BYTE_COUNTS = 279;
  static final int TAG_PLANAR_CONFIGURATION = 284;
  static final int TAG_TILE_WIDTH = 322;
  static final int TAG_TILE_LENGTH = 323;
  static final int TAG_TILE_OFFSETS = 324;
  static final int TAG_TILE_BYTE_COUNTS = 325;
  static final int TAG_SAMPLE_FORMAT = 339;
  static final int TAG_MODEL_PIXEL_SCALE = 33550;
  static final int TAG_MODEL_TIEPOINT = 33922;
  static final int TAG_MODEL_TRANSFORMATION = 34264;
  static final int TAG_GEO_KEY_DIRECTORY = 34735;
  static final int TAG_GEO_DOUBLE_PARAMS = 34736;
  static final int TAG_GEO_ASCII_PARAMS = 34737;

  /** One IFD: TIFF fields plus the tile index arrays managed by the writer. */
  static final class Directory {
    final TreeMap<Integer, TIFFField> fields = new TreeMap<>();
    long[] tileOffsets = new long[0];
    long[] tileByteCounts = new long[0];

    Directory() {}

    Directory copy() {
      Directory copy = new Directory();
      copy.fields.putAll(fields);
      return copy;
    }

    void set(TIFFField field) {
      fields.put(field.getTagNumber(), field);
    }

    void remove(int tag) {
      fields.remove(tag);
    }

    /** All fields including the generated tile index arrays, ordered by tag. */
    List<TIFFField> allFields(boolean bigTiff) {
      TreeMap<Integer, TIFFField> all = new TreeMap<>(fields);
      all.put(TAG_TILE_OFFSETS, tileField("TileOffsets", TAG_TILE_OFFSETS, tileOffsets, bigTiff));
      all.put(
          TAG_TILE_BYTE_COUNTS,
          tileField("TileByteCounts", TAG_TILE_BYTE_COUNTS, tileByteCounts, bigTiff));
      return new ArrayList<>(all.values());
    }

    private static TIFFField tileField(String name, int tag, long[] values, boolean bigTiff) {
      int type = bigTiff ? TIFFTag.TIFF_LONG8 : TIFFTag.TIFF_LONG;
      return new TIFFField(new TIFFTag(name, tag, 1 << type), type, values.length, values);
    }
  }

  private final Path target;
  private final boolean bigTiff;
  private final List<Directory> directories;
  private final List<List<TIFFField>> fields = new ArrayList<>();
  private final byte[] ghost;
  private final long inlineLimit;
  private final long externalAlignment;
  private final long[] ifdOffsets;
  private final List<Map<Integer, Long>> externalOffsets = new ArrayList<>();
  private long dataStart;
  private FileImageOutputStream stream;

  CogTiff(Path target, boolean bigTiff, List<Directory> directories, String ghostMetadata)
      throws IOException {
    this.target = target;
    this.bigTiff = bigTiff;
    this.directories = List.copyOf(directories);
    this.ghost =
        ghostMetadata == null ? new byte[0] : ghostMetadata.getBytes(StandardCharsets.ISO_8859_1);
    this.inlineLimit = bigTiff ? 8 : 4;
    this.externalAlignment = bigTiff ? 8 : 4;
    this.ifdOffsets = new long[directories.size()];
    for (Directory directory : directories) fields.add(directory.allFields(bigTiff));
    plan();
  }

  private void plan() {
    long position = (bigTiff ? 16 : 8) + ghost.length;
    if (position % 2 != 0) position++;
    for (int i = 0; i < directories.size(); i++) {
      ifdOffsets[i] = position;
      position += ifdSize(fields.get(i));
    }
    position = align(position, externalAlignment);
    for (int i = 0; i < fields.size(); i++) {
      Map<Integer, Long> offsets = new LinkedHashMap<>();
      externalOffsets.add(offsets);
      for (TIFFField field : fields.get(i)) {
        long size = valueSize(field);
        if (size > inlineLimit) {
          position = align(position, externalAlignment);
          offsets.put(field.getTagNumber(), position);
          position += size;
        }
      }
    }
    dataStart = align(position, 8);
  }

  private long ifdSize(List<TIFFField> fields) {
    long entries = fields.size();
    return bigTiff ? 8 + entries * 20 + 8 : 2 + entries * 12 + 4;
  }

  private static long align(long value, long alignment) {
    return (value + alignment - 1) / alignment * alignment;
  }

  /** Opens the target and positions the stream for tile data. */
  void open() throws IOException {
    stream = new FileImageOutputStream(target.toFile());
    stream.setByteOrder(ByteOrder.LITTLE_ENDIAN);
    stream.seek(dataStart);
  }

  /** Writes the header, the external tag values and all IFDs. Tile data must be complete. */
  void writeFrontArea() throws IOException {
    stream.seek(0);
    stream.writeByte('I');
    stream.writeByte('I');
    if (bigTiff) {
      stream.writeShort(43);
      stream.writeShort(8);
      stream.writeShort(0);
      stream.writeLong(ifdOffsets[0]);
    } else {
      stream.writeShort(42);
      stream.writeInt((int) ifdOffsets[0]);
    }
    stream.write(ghost);
    for (int i = 0; i < fields.size(); i++)
      for (TIFFField field : fields.get(i)) {
        Long offset = externalOffsets.get(i).get(field.getTagNumber());
        if (offset == null) continue;
        stream.seek(offset);
        stream.write(encode(field));
      }
    for (int i = 0; i < fields.size(); i++) {
      stream.seek(ifdOffsets[i]);
      long entries = fields.get(i).size();
      if (bigTiff) stream.writeLong(entries);
      else stream.writeShort((int) entries);
      for (TIFFField field : fields.get(i)) {
        stream.writeShort(field.getTagNumber());
        stream.writeShort(field.getType());
        long count = count(field);
        if (bigTiff) stream.writeLong(count);
        else stream.writeInt((int) count);
        Long offset = externalOffsets.get(i).get(field.getTagNumber());
        if (offset != null) {
          if (bigTiff) stream.writeLong(offset);
          else stream.writeInt(offset.intValue());
        } else {
          byte[] value = encode(field);
          stream.write(value);
          for (int pad = value.length; pad < inlineLimit; pad++) stream.writeByte(0);
        }
      }
      long next = i + 1 < fields.size() ? ifdOffsets[i + 1] : 0;
      if (bigTiff) stream.writeLong(next);
      else stream.writeInt((int) next);
    }
    stream.flush();
  }

  /**
   * Appends one tile with a uint32 little-endian size leader and a repeated-last-4-bytes trailer.
   * Returns the offset of the tile payload.
   */
  long writeBlock(byte[] data, int length) throws IOException {
    stream.writeInt(length);
    long offset = stream.getStreamPosition();
    stream.write(data, 0, length);
    byte[] trailer = new byte[4];
    if (length >= 4) System.arraycopy(data, length - 4, trailer, 0, 4);
    else System.arraycopy(data, 0, trailer, 0, length);
    stream.write(trailer);
    return offset;
  }

  /** TIFF count of a field; ASCII counts bytes including the NUL terminators. */
  static long count(TIFFField field) {
    if (field.getType() != TIFFTag.TIFF_ASCII) return field.getCount();
    long count = 0;
    for (int i = 0; i < field.getCount(); i++)
      count += field.getAsString(i).length() + 1L;
    return count;
  }

  static long valueSize(TIFFField field) {
    if (field.getType() == TIFFTag.TIFF_ASCII) return count(field);
    return count(field) * TIFFTag.getSizeOfType(field.getType());
  }

  static byte[] encode(TIFFField field) {
    if (field.getType() == TIFFTag.TIFF_ASCII) {
      StringBuilder text = new StringBuilder();
      for (int i = 0; i < field.getCount(); i++) {
        String value = field.getAsString(i);
        for (int c = 0; c < value.length(); c++) text.append((char) (value.charAt(c) & 0xff));
        text.append('\0');
      }
      return text.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
    int count = field.getCount();
    ByteBuffer buffer =
        ByteBuffer.allocate(count * TIFFTag.getSizeOfType(field.getType()))
            .order(ByteOrder.LITTLE_ENDIAN);
    switch (field.getType()) {
      case TIFFTag.TIFF_BYTE, TIFFTag.TIFF_SBYTE, TIFFTag.TIFF_UNDEFINED -> {
        byte[] bytes = field.getAsBytes();
        buffer.put(bytes, 0, Math.min(count, bytes.length));
      }
      case TIFFTag.TIFF_SHORT -> {
        char[] values = field.getAsChars();
        for (int i = 0; i < count; i++) buffer.putShort((short) values[i]);
      }
      case TIFFTag.TIFF_SSHORT -> {
        short[] values = field.getAsShorts();
        for (int i = 0; i < count; i++) buffer.putShort(values[i]);
      }
      case TIFFTag.TIFF_LONG, TIFFTag.TIFF_SLONG, TIFFTag.TIFF_IFD_POINTER -> {
        long[] values = field.getAsLongs();
        for (int i = 0; i < count; i++) buffer.putInt((int) values[i]);
      }
      case TIFFTag.TIFF_LONG8, TIFFTag.TIFF_SLONG8, TIFFTag.TIFF_IFD8 -> {
        long[] values = field.getAsLongs();
        for (int i = 0; i < count; i++) buffer.putLong(values[i]);
      }
      case TIFFTag.TIFF_FLOAT -> {
        float[] values = field.getAsFloats();
        for (int i = 0; i < count; i++) buffer.putFloat(values[i]);
      }
      case TIFFTag.TIFF_DOUBLE -> {
        double[] values = field.getAsDoubles();
        for (int i = 0; i < count; i++) buffer.putDouble(values[i]);
      }
      case TIFFTag.TIFF_RATIONAL -> {
        long[][] values = field.getAsRationals();
        for (int i = 0; i < count; i++) {
          buffer.putInt((int) values[i][0]);
          buffer.putInt((int) values[i][1]);
        }
      }
      case TIFFTag.TIFF_SRATIONAL -> {
        int[][] values = field.getAsSRationals();
        for (int i = 0; i < count; i++) {
          buffer.putInt(values[i][0]);
          buffer.putInt(values[i][1]);
        }
      }
      default -> throw new IllegalArgumentException("Unsupported TIFF type " + field.getType());
    }
    return buffer.array();
  }

  @Override
  public void close() {
    if (stream != null) {
      try {
        stream.close();
      } catch (IOException ignored) {
      }
    }
  }
}
