package ch.so.agi.hop.raster.geotools;

import it.geosolutions.imageio.plugins.tiff.TIFFDirectory;
import it.geosolutions.imageio.plugins.tiff.TIFFField;
import it.geosolutions.imageio.plugins.tiff.TIFFImageWriteParam;
import it.geosolutions.imageio.plugins.tiff.TIFFTag;
import it.geosolutions.imageioimpl.plugins.tiff.TIFFImageWriterSpi;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.geom.AffineTransform;
import java.awt.image.*;
import java.nio.file.Path;
import java.util.function.IntConsumer;
import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.event.IIOWriteProgressListener;
import javax.imageio.stream.FileImageOutputStream;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.coverage.grid.io.imageio.geotiff.CRS2GeoTiffMetadataAdapter;
import org.geotools.coverage.grid.io.imageio.geotiff.GeoTiffConstants;
import org.geotools.gce.geotiff.GeoTiffWriteParams;
import org.geotools.gce.geotiff.GeoTiffWriter;

/** GeoTools georeferencing with explicit TIFF tags for raw samples, colors and alpha. */
final class GeoTiffOutput {
  private GeoTiffOutput() {}

  static void write(
      Path file,
      RenderedImage image,
      RasterSource source,
      int band,
      double noData,
      GeoTiffWriteParams options)
      throws Exception {
    write(
        file,
        image,
        source.crs(),
        (AffineTransform) source.gridToWorld(),
        new double[] {source.scale(band)},
        new double[] {source.offset(band)},
        noData,
        RasterColorInfo.numeric(),
        options);
  }

  static void write(
      Path file,
      RenderedImage image,
      CoordinateReferenceSystem crs,
      AffineTransform gridToWorld,
      double[] scales,
      double[] offsets,
      Double noData,
      RasterColorInfo color,
      GeoTiffWriteParams options)
      throws Exception {
    write(file, image, crs, gridToWorld, scales, offsets, noData, color, options, ignored -> {});
  }

  static void write(
      Path file,
      RenderedImage image,
      CoordinateReferenceSystem crs,
      AffineTransform gridToWorld,
      double[] scales,
      double[] offsets,
      Double noData,
      RasterColorInfo color,
      GeoTiffWriteParams options,
      IntConsumer progress)
      throws Exception {
    var encoder = new CRS2GeoTiffMetadataAdapter(crs).parseCoordinateReferenceSystem();
    var transform = new AffineTransform(gridToWorld);
    // GeoTIFF indices start at zero and refer to the upper-left pixel corner.
    transform.translate(image.getMinX() - .5, image.getMinY() - .5);
    encoder.addGeoShortParam(
        GeoTiffConstants.GTRasterTypeGeoKey, GeoTiffConstants.RasterPixelIsArea);
    encoder.setModelTransformation(transform);
    if (noData != null) encoder.setNoData(noData);
    var writer = new TIFFImageWriterSpi().createWriterInstance();
    IIOWriteProgressListener listener = progressListener(progress);
    writer.addIIOWriteProgressListener(listener);
    try (var stream = new FileImageOutputStream(file.toFile())) {
      var params = (TIFFImageWriteParam) options.getAdaptee();
      params.setForceToBigTIFF(options.isForceToBigTIFF());
      var metadata =
          GeoTiffWriter.createGeoTiffIIOMetadata(
              writer, ImageTypeSpecifier.createFromRenderedImage(image), encoder, params);
      var directory = TIFFDirectory.createFromMetadata(metadata);
      int bands = image.getSampleModel().getNumBands();
      int photo =
          switch (color.kind()) {
            case RGB -> 2;
            case PALETTE -> 3;
            default -> 1;
          };
      shorts(directory, "PhotometricInterpretation", 262, new char[] {(char) photo});
      directory.removeTIFFField(338);
      int baseBands = photo == 2 ? 3 : 1;
      if (bands > baseBands) {
        char[] extras = new char[bands - baseBands];
        if (color.alphaBand() >= baseBands)
          extras[color.alphaBand() - baseBands] = (char) (color.associatedAlpha() ? 1 : 2);
        shorts(directory, "ExtraSamples", 338, extras);
      }
      if (photo == 3) {
        char[] original = color.colorMap();
        int size = 1 << DataBuffer.getDataTypeSize(image.getSampleModel().getDataType());
        char[] palette = new char[size * 3];
        int entries = original.length / 3;
        for (int channel = 0; channel < 3; channel++)
          System.arraycopy(original, channel * entries, palette, channel * size, entries);
        shorts(directory, "ColorMap", 320, palette);
      } else directory.removeTIFFField(320);
      StringBuilder xml = new StringBuilder("<GDALMetadata>");
      boolean hasMetadata = false;
      for (int band = 0; band < bands; band++) {
        if (scales[band] == 1 && offsets[band] == 0) continue;
        hasMetadata = true;
        xml.append("<Item name=\"SCALE\" sample=\"")
            .append(band)
            .append("\" role=\"scale\">")
            .append(scales[band])
            .append("</Item><Item name=\"OFFSET\" sample=\"")
            .append(band)
            .append("\" role=\"offset\">")
            .append(offsets[band])
            .append("</Item>");
      }
      if (hasMetadata) {
        xml.append("</GDALMetadata>");
        directory.addTIFFField(
            new TIFFField(
                new TIFFTag("GDAL_METADATA", 42112, 1 << TIFFTag.TIFF_ASCII),
                TIFFTag.TIFF_ASCII,
                1,
                new String[] {xml.toString()}));
      }
      writer.setOutput(stream);
      writer.write(
          writer.getDefaultStreamMetadata(params),
          new IIOImage(image, null, directory.getAsMetadata()),
          params);
    } finally {
      writer.removeIIOWriteProgressListener(listener);
      writer.dispose();
    }
    // ImageIO reconstructs ColorMap from IndexColorModel's 8-bit components during write,
    // overriding supplied metadata. Restore the original UInt16 entries in the temporary file.
    if (color.kind() == RasterColorInfo.Kind.PALETTE)
      restorePalette(file, color.colorMap(), image.getSampleModel().getDataType());
  }

  private static IIOWriteProgressListener progressListener(IntConsumer progress) {
    IntConsumer sink = progress == null ? ignored -> {} : progress;
    return new IIOWriteProgressListener() {
      @Override
      public void imageStarted(ImageWriter source, int imageIndex) {}

      @Override
      public void imageProgress(ImageWriter source, float percentageDone) {
        sink.accept(Math.max(0, Math.min(100, Math.round(percentageDone))));
      }

      @Override
      public void imageComplete(ImageWriter source) {
        sink.accept(100);
      }

      @Override
      public void thumbnailStarted(ImageWriter source, int imageIndex, int thumbnailIndex) {}

      @Override
      public void thumbnailProgress(ImageWriter source, float percentageDone) {}

      @Override
      public void thumbnailComplete(ImageWriter source) {}

      @Override
      public void writeAborted(ImageWriter source) {}
    };
  }

  private static void restorePalette(Path file, char[] original, int type)
      throws java.io.IOException {
    try (var stream = new javax.imageio.stream.FileImageOutputStream(file.toFile())) {
      int marker = stream.readUnsignedShort();
      if (marker != 0x4949 && marker != 0x4d4d)
        throw new java.io.IOException("Invalid TIFF byte order");
      stream.setByteOrder(
          marker == 0x4949 ? java.nio.ByteOrder.LITTLE_ENDIAN : java.nio.ByteOrder.BIG_ENDIAN);
      int version = stream.readUnsignedShort();
      boolean big = version == 43;
      if (version != 42 && !big) throw new java.io.IOException("Invalid TIFF header");
      long ifd;
      if (big) {
        if (stream.readUnsignedShort() != 8 || stream.readUnsignedShort() != 0)
          throw new java.io.IOException("Invalid BigTIFF header");
        ifd = stream.readLong();
      } else ifd = stream.readUnsignedInt();
      stream.seek(ifd);
      long entries = big ? stream.readLong() : stream.readUnsignedShort();
      long start = stream.getStreamPosition();
      int size = 1 << DataBuffer.getDataTypeSize(type);
      for (long i = 0; i < entries; i++) {
        stream.seek(start + i * (big ? 20 : 12));
        int tag = stream.readUnsignedShort(), fieldType = stream.readUnsignedShort();
        long count = big ? stream.readLong() : stream.readUnsignedInt();
        long offset = big ? stream.readLong() : stream.readUnsignedInt();
        if (tag != 320) continue;
        if (fieldType != TIFFTag.TIFF_SHORT || count != 3L * size)
          throw new java.io.IOException("Unexpected output palette layout");
        stream.seek(offset);
        int n = original.length / 3;
        for (int channel = 0; channel < 3; channel++)
          for (int j = 0; j < size; j++) stream.writeShort(j < n ? original[channel * n + j] : 0);
        return;
      }
      throw new java.io.IOException("Output palette tag missing");
    }
  }

  private static void shorts(TIFFDirectory d, String name, int tag, char[] values) {
    d.addTIFFField(
        new TIFFField(
            new TIFFTag(name, tag, 1 << TIFFTag.TIFF_SHORT),
            TIFFTag.TIFF_SHORT,
            values.length,
            values));
  }

  static ColorModel colorModel(int type, int bands, RasterColorInfo color) {
    if (color.kind() == RasterColorInfo.Kind.PALETTE) {
      char[] map = color.colorMap();
      int count = map.length / 3;
      int[] rgb = new int[count];
      for (int i = 0; i < count; i++)
        rgb[i] =
            0xff000000
                | (map[i] / 257) << 16
                | (map[count + i] / 257) << 8
                | (map[2 * count + i] / 257);
      return new IndexColorModel(DataBuffer.getDataTypeSize(type), count, rgb, 0, false, -1, type);
    }
    boolean alpha = color.alphaBand() >= 0;
    ColorSpace space =
        color.kind() == RasterColorInfo.Kind.RGB
            ? ColorSpace.getInstance(ColorSpace.CS_sRGB)
            : color.kind() == RasterColorInfo.Kind.GRAY_ALPHA || bands == 1
                ? ColorSpace.getInstance(ColorSpace.CS_GRAY)
                : new SampleColorSpace(bands);
    return new ComponentColorModel(
        space, alpha, false, alpha ? Transparency.TRANSLUCENT : Transparency.OPAQUE, type);
  }

  /** A storage-only color space prevents interpreting arbitrary numeric triples as RGB. */
  private static final class SampleColorSpace extends ColorSpace {
    SampleColorSpace(int bands) {
      super(ColorSpace.TYPE_FCLR, bands);
    }

    public float[] toRGB(float[] value) {
      return new float[] {value[0], value[0], value[0]};
    }

    public float[] fromRGB(float[] rgb) {
      float[] v = new float[getNumComponents()];
      java.util.Arrays.fill(v, rgb[0]);
      return v;
    }

    public float[] toCIEXYZ(float[] value) {
      return ColorSpace.getInstance(CS_sRGB).toCIEXYZ(toRGB(value));
    }

    public float[] fromCIEXYZ(float[] xyz) {
      return fromRGB(ColorSpace.getInstance(CS_sRGB).fromCIEXYZ(xyz));
    }
  }
}
