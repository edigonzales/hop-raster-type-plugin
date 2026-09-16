package ch.so.agi.hop.raster.geotools;

import java.awt.image.DataBuffer;

final class RasterSampleValues {
  private RasterSampleValues() {}

  static double convert(double value, int type) {
    if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite interpolated value");
    if (type == DataBuffer.TYPE_DOUBLE) return value;
    if (type == DataBuffer.TYPE_FLOAT) {
      float f = (float) value;
      if (!Float.isFinite(f)) throw new IllegalArgumentException("Float32 output overflow");
      return f;
    }
    double rounded = Math.rint(value);
    double min =
        switch (type) {
          case DataBuffer.TYPE_SHORT -> Short.MIN_VALUE;
          case DataBuffer.TYPE_INT -> Integer.MIN_VALUE;
          default -> 0;
        };
    double max =
        switch (type) {
          case DataBuffer.TYPE_BYTE -> 255;
          case DataBuffer.TYPE_USHORT -> 65535;
          case DataBuffer.TYPE_SHORT -> Short.MAX_VALUE;
          case DataBuffer.TYPE_INT -> Integer.MAX_VALUE;
          default -> throw new IllegalArgumentException("Unsupported sample type");
        };
    if (rounded < min || rounded > max)
      throw new IllegalArgumentException("Integer output overflow");
    return rounded;
  }

  static void validateNoData(double value, int type) {
    if (Double.isNaN(value) && (type == DataBuffer.TYPE_FLOAT || type == DataBuffer.TYPE_DOUBLE))
      return;
    if (convert(value, type) != value)
      throw new IllegalArgumentException("NoData is not exactly representable in output type");
  }
}
