package ch.so.agi.hop.raster.geotools;

import java.io.IOException;
import javax.imageio.stream.ImageOutputStream;

/** One padded, pixel-interleaved tile to compressed TIFF block bytes. */
interface CogTileEncoder {
  int encode(ImageOutputStream stream, byte[] raw, int width, int height, int stride)
      throws IOException;
}
