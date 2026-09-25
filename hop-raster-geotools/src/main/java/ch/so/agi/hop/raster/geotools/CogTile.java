package ch.so.agi.hop.raster.geotools;

import java.awt.Point;
import java.awt.image.BandedSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferDouble;
import java.awt.image.DataBufferFloat;
import java.awt.image.DataBufferInt;
import java.awt.image.DataBufferShort;
import java.awt.image.DataBufferUShort;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/** TIFF sample layout helpers: little-endian chunky tiles and data-type conversion. */
final class CogTile {
  private CogTile() {}

  static int bitsPerSample(int dataType) {
    return switch (dataType) {
      case DataBuffer.TYPE_BYTE -> 8;
      case DataBuffer.TYPE_USHORT, DataBuffer.TYPE_SHORT -> 16;
      case DataBuffer.TYPE_INT, DataBuffer.TYPE_FLOAT -> 32;
      case DataBuffer.TYPE_DOUBLE -> 64;
      default -> throw new IllegalArgumentException("Unsupported TIFF data type " + dataType);
    };
  }

  static int sampleBytes(int dataType) {
    return bitsPerSample(dataType) / 8;
  }

  static int sampleFormat(int dataType) {
    return switch (dataType) {
      case DataBuffer.TYPE_BYTE, DataBuffer.TYPE_USHORT -> 1;
      case DataBuffer.TYPE_SHORT, DataBuffer.TYPE_INT -> 2;
      case DataBuffer.TYPE_FLOAT, DataBuffer.TYPE_DOUBLE -> 3;
      default -> throw new IllegalArgumentException("Unsupported TIFF data type " + dataType);
    };
  }

  /** Reads one little-endian sample from the raw chunky tile. */
  static double sample(byte[] raw, int sampleIndex, int dataType, int bytes) {
    int offset = sampleIndex * bytes;
    return switch (dataType) {
      case DataBuffer.TYPE_BYTE -> raw[offset] & 0xff;
      case DataBuffer.TYPE_USHORT ->
          ((raw[offset] & 0xff) | (raw[offset + 1] & 0xff) << 8) & 0xffff;
      case DataBuffer.TYPE_SHORT ->
          (short) ((raw[offset] & 0xff) | raw[offset + 1] << 8);
      case DataBuffer.TYPE_INT ->
          (raw[offset] & 0xff)
              | (raw[offset + 1] & 0xff) << 8
              | (raw[offset + 2] & 0xff) << 16
              | raw[offset + 3] << 24;
      case DataBuffer.TYPE_FLOAT -> Float.intBitsToFloat(readInt(raw, offset));
      case DataBuffer.TYPE_DOUBLE -> Double.longBitsToDouble(readLong(raw, offset));
      default -> throw new IllegalArgumentException("Unsupported TIFF data type " + dataType);
    };
  }

  private static int readInt(byte[] raw, int offset) {
    return (raw[offset] & 0xff)
        | (raw[offset + 1] & 0xff) << 8
        | (raw[offset + 2] & 0xff) << 16
        | raw[offset + 3] << 24;
  }

  private static long readLong(byte[] raw, int offset) {
    return (readInt(raw, offset) & 0xffffffffL) | (long) readInt(raw, offset + 4) << 32;
  }

  /**
   * Packs one padded tile as little-endian, pixel-interleaved samples. Values pass through the
   * same Java2D sample conversion as the ImageIO-backed writer.
   */
  static void pack(double[][] samples, int block, int bands, int dataType, byte[] target) {
    int pixels = block * block;
    int bytes = sampleBytes(dataType);
    WritableRaster scratch =
        Raster.createWritableRaster(
            new BandedSampleModel(dataType, block, block, 1), new Point(0, 0));
    for (int band = 0; band < bands; band++) {
      scratch.setSamples(0, 0, block, block, 0, samples[band]);
      switch (dataType) {
        case DataBuffer.TYPE_BYTE -> {
          byte[] values = ((DataBufferByte) scratch.getDataBuffer()).getData();
          for (int p = 0; p < pixels; p++) target[p * bands + band] = values[p];
        }
        case DataBuffer.TYPE_USHORT -> {
          short[] values = ((DataBufferUShort) scratch.getDataBuffer()).getData();
          for (int p = 0; p < pixels; p++) {
            int offset = (p * bands + band) * 2;
            target[offset] = (byte) values[p];
            target[offset + 1] = (byte) (values[p] >>> 8);
          }
        }
        case DataBuffer.TYPE_SHORT -> {
          short[] values = ((DataBufferShort) scratch.getDataBuffer()).getData();
          for (int p = 0; p < pixels; p++) {
            int offset = (p * bands + band) * 2;
            target[offset] = (byte) values[p];
            target[offset + 1] = (byte) (values[p] >> 8);
          }
        }
        case DataBuffer.TYPE_INT -> {
          int[] values = ((DataBufferInt) scratch.getDataBuffer()).getData();
          for (int p = 0; p < pixels; p++) {
            int value = values[p];
            int offset = (p * bands + band) * 4;
            target[offset] = (byte) value;
            target[offset + 1] = (byte) (value >>> 8);
            target[offset + 2] = (byte) (value >>> 16);
            target[offset + 3] = (byte) (value >>> 24);
          }
        }
        case DataBuffer.TYPE_FLOAT -> {
          float[] values = ((DataBufferFloat) scratch.getDataBuffer()).getData();
          for (int p = 0; p < pixels; p++) {
            int value = Float.floatToIntBits(values[p]);
            int offset = (p * bands + band) * 4;
            target[offset] = (byte) value;
            target[offset + 1] = (byte) (value >>> 8);
            target[offset + 2] = (byte) (value >>> 16);
            target[offset + 3] = (byte) (value >>> 24);
          }
        }
        case DataBuffer.TYPE_DOUBLE -> {
          double[] values = ((DataBufferDouble) scratch.getDataBuffer()).getData();
          for (int p = 0; p < pixels; p++) {
            long value = Double.doubleToLongBits(values[p]);
            int offset = (p * bands + band) * 8;
            for (int i = 0; i < 8; i++) target[offset + i] = (byte) (value >>> (8 * i));
          }
        }
        default -> throw new IllegalArgumentException("Unsupported TIFF data type " + dataType);
      }
    }
  }
}
