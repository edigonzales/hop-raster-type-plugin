package ch.so.agi.hop.raster.geotools;

import java.awt.Rectangle;
import java.awt.image.Raster;
import java.io.IOException;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;

public interface RasterSource extends AutoCloseable {
  Rectangle bounds();

  CoordinateReferenceSystem crs();

  MathTransform gridToWorld();

  int bands();

  default RasterColorInfo colorInfo() {
    return RasterColorInfo.numeric();
  }

  Raster read(RasterReadRequest request) throws Exception;

  boolean valid(double value, int band);

  double physical(double value, int band);

  Double noData(int band);

  default double scale(int band) {
    return 1;
  }

  default double offset(int band) {
    return 0;
  }

  default int dataType() {
    return java.awt.image.DataBuffer.TYPE_DOUBLE;
  }

  @Override
  void close() throws IOException;
}
