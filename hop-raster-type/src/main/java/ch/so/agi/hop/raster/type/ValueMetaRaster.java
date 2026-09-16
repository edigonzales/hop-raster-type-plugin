package ch.so.agi.hop.raster.type;

import ch.so.agi.hop.raster.*;
import java.io.*;
import java.util.Arrays;
import org.apache.hop.core.exception.*;
import org.apache.hop.core.row.IValueMeta;
import org.apache.hop.core.row.value.ValueMetaBase;
import org.apache.hop.core.row.value.ValueMetaPlugin;

@ValueMetaPlugin(
    id = "" + ValueMetaRaster.TYPE_RASTER,
    name = "Raster",
    description = "A referenced raster dataset",
    classLoaderGroup = "sogeo-geometry")
public final class ValueMetaRaster extends ValueMetaBase {
  public static final int TYPE_RASTER = 727837;

  public ValueMetaRaster() {
    this(null);
  }

  public ValueMetaRaster(String name) {
    super(name, TYPE_RASTER);
  }

  @Override
  public Class<?> getNativeDataTypeClass() {
    return RasterDataset.class;
  }

  public RasterDataset getRaster(Object value) throws HopValueException {
    if (value == null || value instanceof RasterDataset) return (RasterDataset) value;
    throw new HopValueException("Expected a Raster value; use Raster Reader for paths");
  }

  @Override
  public Object cloneValueData(Object value) throws HopValueException {
    return getRaster(value);
  }

  @Override
  public String getString(Object value) throws HopValueException {
    return value == null ? null : getRaster(value).toString();
  }

  @Override
  public Object getNativeDataType(Object value) throws HopValueException {
    return getRaster(value);
  }

  @Override
  public Object convertData(IValueMeta source, Object value) throws HopValueException {
    if (value == null) return null;
    if (source.getType() == TYPE_RASTER) return getRaster(value);
    throw new HopValueException("Use Raster Reader to construct Raster values");
  }

  @Override
  public byte[] getBinary(Object value) throws HopValueException {
    try {
      return RasterCodec.encode(getRaster(value));
    } catch (IOException e) {
      throw new HopValueException("Cannot encode raster", e);
    }
  }

  @Override
  public int compare(Object a, Object b) throws HopValueException {
    if (a == b) return 0;
    if (a == null) return -1;
    if (b == null) return 1;
    int comparison = Arrays.compareUnsigned(getBinary(a), getBinary(b));
    return isSortedDescending() ? -comparison : comparison;
  }

  @Override
  public int hashCode(Object value) throws HopValueException {
    return value == null ? 0 : Arrays.hashCode(getBinary(value));
  }

  @Override
  public void writeData(DataOutputStream out, Object value) throws HopFileException {
    try {
      byte[] bytes = RasterCodec.encode(getRaster(value));
      out.writeInt(bytes.length);
      out.write(bytes);
    } catch (Exception e) {
      throw new HopFileException("Cannot write Raster value", e);
    }
  }

  @Override
  public Object readData(DataInputStream in) throws HopFileException {
    try {
      int n = in.readInt();
      if (n < 0 || n > RasterCodec.MAX_BYTES) throw new IOException("Invalid Raster length");
      byte[] bytes = new byte[n];
      in.readFully(bytes);
      return RasterCodec.decode(bytes);
    } catch (Exception e) {
      throw new HopFileException("Cannot read Raster value", e);
    }
  }
}
