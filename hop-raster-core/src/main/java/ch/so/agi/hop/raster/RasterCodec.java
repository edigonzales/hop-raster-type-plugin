package ch.so.agi.hop.raster;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit bounded wire format; never invokes Java object deserialization. */
public final class RasterCodec {
  public static final int MAX_BYTES = 16 * 1024 * 1024;

  private RasterCodec() {}

  public static byte[] encode(RasterDataset value) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var out =
        new DataOutputStream(
            new FilterOutputStream(bytes) {
              private int written;

              private void reserve(int count) throws IOException {
                if (count > MAX_BYTES - written)
                  throw new IOException("Raster value exceeds size limit");
                written += count;
              }

              @Override
              public void write(int b) throws IOException {
                reserve(1);
                out.write(b);
              }

              @Override
              public void write(byte[] b, int off, int len) throws IOException {
                reserve(len);
                out.write(b, off, len);
              }
            })) {
      out.writeInt(0x52535431);
      out.writeBoolean(value != null);
      if (value != null) {
        text(out, value.source().location());
        text(out, value.source().identity());
        descriptor(out, value.descriptor());
        out.writeInt(value.steps().size());
        for (var step : value.steps()) {
          var op = step.operation();
          out.writeByte(op instanceof RasterOperation.Clip ? 1 : 2);
          text(out, op.origin());
          if (op instanceof RasterOperation.Clip c) {
            text(out, c.polygonWkt());
            out.writeBoolean(c.polygonMask());
            out.writeInt(c.bands().size());
            for (int b : c.bands()) out.writeInt(b);
            number(out, c.noData());
          } else if (op instanceof RasterOperation.Reproject r) {
            text(out, r.targetCrs());
            out.writeDouble(r.resolutionX());
            out.writeDouble(r.resolutionY());
            out.writeInt(r.extent().size());
            for (double v : r.extent()) out.writeDouble(v);
            text(out, r.interpolation());
            text(out, r.outputType());
            number(out, r.sourceNoData());
            number(out, r.outputNoData());
          }
          descriptor(out, step.output());
          if (bytes.size() > MAX_BYTES) throw new IOException("Raster value exceeds size limit");
        }
      }
    }
    if (bytes.size() > MAX_BYTES) throw new IOException("Raster value exceeds size limit");
    return bytes.toByteArray();
  }

  public static RasterDataset decode(byte[] bytes) throws IOException {
    if (bytes.length > MAX_BYTES) throw new IOException("Raster value exceeds size limit");
    try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (in.readInt() != 0x52535431) throw new IOException("Unsupported raster wire version");
      RasterDataset result = null;
      if (in.readBoolean()) {
        var ref = new RasterReference(text(in), text(in));
        var d = descriptor(in);
        var steps = new ArrayList<RasterDataset.Step>();
        int n = count(in, 128);
        for (int i = 0; i < n; i++) {
          int tag = in.readUnsignedByte();
          String origin = text(in);
          RasterOperation op;
          if (tag == 1) {
            String wkt = text(in);
            boolean mask = in.readBoolean();
            int bands = count(in, 4096);
            var selected = new ArrayList<Integer>();
            for (int b = 0; b < bands; b++) selected.add(in.readInt());
            op = new RasterOperation.Clip(origin, wkt, mask, selected, number(in));
          } else if (tag == 2) {
            String crs = text(in);
            double rx = in.readDouble(), ry = in.readDouble();
            int en = count(in, 4);
            var extent = new ArrayList<Double>();
            for (int e = 0; e < en; e++) extent.add(in.readDouble());
            op =
                new RasterOperation.Reproject(
                    origin, crs, rx, ry, extent, text(in), text(in), number(in), number(in));
          } else throw new IOException("Unknown raster operation");
          steps.add(new RasterDataset.Step(op, descriptor(in)));
        }
        result = new RasterDataset(ref, d, steps);
      }
      if (in.available() != 0) throw new IOException("Trailing raster data");
      return result;
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new IOException("Invalid raster value", e);
    }
  }

  private static void descriptor(DataOutputStream o, RasterDescriptor d) throws IOException {
    var g = d.grid();
    o.writeInt(g.width());
    o.writeInt(g.height());
    for (double v : new double[] {g.m00(), g.m10(), g.m01(), g.m11(), g.m02(), g.m12()})
      o.writeDouble(v);
    text(o, d.crsWkt());
    text(o, d.authority());
    o.writeInt(d.bands().size());
    for (var b : d.bands()) {
      text(o, b.name());
      o.writeInt(b.dataType());
      number(o, b.noData());
      o.writeDouble(b.scale());
      o.writeDouble(b.offset());
    }
    text(o, d.color());
    o.writeInt(d.alphaBand());
    o.writeBoolean(d.associatedAlpha());
    o.writeInt(d.palette().size());
    for (int v : d.palette()) o.writeInt(v);
  }

  private static RasterDescriptor descriptor(DataInputStream i) throws IOException {
    var g =
        new RasterGrid(
            i.readInt(),
            i.readInt(),
            i.readDouble(),
            i.readDouble(),
            i.readDouble(),
            i.readDouble(),
            i.readDouble(),
            i.readDouble());
    String crs = text(i), auth = text(i);
    int n = count(i, 4096);
    var bands = new ArrayList<RasterBand>();
    for (int b = 0; b < n; b++)
      bands.add(new RasterBand(text(i), i.readInt(), number(i), i.readDouble(), i.readDouble()));
    String color = text(i);
    int alpha = i.readInt();
    boolean associated = i.readBoolean();
    n = count(i, 196608);
    var palette = new ArrayList<Integer>();
    for (int p = 0; p < n; p++) palette.add(i.readInt());
    return new RasterDescriptor(g, crs, auth, bands, color, alpha, associated, palette);
  }

  private static int count(DataInputStream in, int max) throws IOException {
    int n = in.readInt();
    if (n < 0 || n > max) throw new IOException("Invalid raster collection length");
    return n;
  }

  private static void number(DataOutputStream o, Double d) throws IOException {
    o.writeBoolean(d != null);
    if (d != null) o.writeDouble(d);
  }

  private static Double number(DataInputStream i) throws IOException {
    return i.readBoolean() ? i.readDouble() : null;
  }

  private static void text(DataOutputStream o, String s) throws IOException {
    if (s == null) {
      o.writeInt(-1);
      return;
    }
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    if (b.length > MAX_BYTES) throw new IOException("Raster text exceeds limit");
    o.writeInt(b.length);
    o.write(b);
  }

  private static String text(DataInputStream i) throws IOException {
    int n = i.readInt();
    if (n == -1) return null;
    if (n < 0 || n > MAX_BYTES || n > i.available())
      throw new IOException("Invalid raster string length");
    byte[] b = i.readNBytes(n);
    return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString();
  }
}
