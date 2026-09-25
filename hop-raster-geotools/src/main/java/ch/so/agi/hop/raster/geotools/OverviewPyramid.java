package ch.so.agi.hop.raster.geotools;

import ch.so.agi.hop.raster.RasterWriteOptions;
import java.awt.image.DataBuffer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Bounded, lossless overview cascade shared by GeoTIFF and COG output. */
final class OverviewPyramid {
  private OverviewPyramid() {}

  record Level(CogBlockStore lossless, CogBlockStore output) implements AutoCloseable {
    public void close() {
      try {
        output.close();
      } finally {
        if (lossless != output) lossless.close();
      }
    }
  }

  /**
   * Generates one overview level. The lossless store feeds the next level; the output store holds
   * the blocks for the final file and is only separate when the output codec is lossy.
   */
  static Level generate(
      CogSamples previous,
      int[] dims,
      RasterWriteOptions.Resampling resampling,
      int block,
      int bands,
      int dataType,
      Double[] noData,
      CogTileCodec storeCodec,
      CogJpegCodec jpeg,
      Path directory,
      BooleanSupplier stopped,
      Runnable tileCompleted)
      throws Exception {
    int width = dims[0], height = dims[1];
    int across = (width + block - 1) / block;
    int down = (height + block - 1) / block;
    CogBlockStore store = CogBlockStore.create(directory, ".hop-raster-ovr-", across * down);
    CogBlockStore output = store;
    try {
      if (jpeg != null)
        output = CogBlockStore.create(directory, ".hop-raster-jpeg-", across * down);
      int bytes = CogTile.sampleBytes(dataType);
      int stride = block * bands * bytes;
      byte[] raw = new byte[block * block * bands * bytes];
      double[][] outputPixels = new double[bands][block * block];
      double[][] sums = new double[bands][block * block];
      int[][] counts = new int[bands][block * block];
      boolean average = resampling == RasterWriteOptions.Resampling.AVERAGE;
      CogTileBuffer storeBuffer = new CogTileBuffer();
      CogTileBuffer outputBuffer = new CogTileBuffer();
      for (int ty = 0; ty < down; ty++) {
        for (int tx = 0; tx < across; tx++) {
          checkStopped(stopped);
          int originX = tx * 2 * block, originY = ty * 2 * block;
          for (int band = 0; band < bands; band++) {
            Arrays.fill(outputPixels[band], 0);
            if (average) {
              Arrays.fill(sums[band], 0);
              Arrays.fill(counts[band], 0);
            }
          }
          for (int dy = 0; dy < 2; dy++) {
            for (int dx = 0; dx < 2; dx++) {
              int sx = originX + dx * block, sy = originY + dy * block;
              int sw = Math.min(block, previous.width() - sx);
              int sh = Math.min(block, previous.height() - sy);
              if (sw <= 0 || sh <= 0) continue;
              double[][] input = previous.read(sx, sy, sw, sh);
              for (int band = 0; band < bands; band++) {
                if (average) {
                  for (int row = 0; row < sh; row++) {
                    for (int column = 0; column < sw; column++) {
                      double value = input[band][row * sw + column];
                      if (!previous.valid(value, band)) continue;
                      int ox = (sx + column) / 2 - tx * block;
                      int oy = (sy + row) / 2 - ty * block;
                      sums[band][oy * block + ox] += value;
                      counts[band][oy * block + ox]++;
                    }
                  }
                } else {
                  for (int row = (sy & 1) == 0 ? 0 : 1; row < sh; row += 2) {
                    for (int column = ((sx & 1) == 0 ? 0 : 1); column < sw; column += 2) {
                      int ox = (sx + column) / 2 - tx * block;
                      int oy = (sy + row) / 2 - ty * block;
                      outputPixels[band][oy * block + ox] = input[band][row * sw + column];
                    }
                  }
                }
              }
            }
          }
          if (average) {
            for (int band = 0; band < bands; band++) {
              for (int pixel = 0; pixel < block * block; pixel++) {
                if (counts[band][pixel] == 0) {
                  outputPixels[band][pixel] = invalid(noData[band], dataType);
                } else {
                  double value = sums[band][pixel] / counts[band][pixel];
                  if (dataType != DataBuffer.TYPE_FLOAT && dataType != DataBuffer.TYPE_DOUBLE)
                    value = Math.rint(value);
                  outputPixels[band][pixel] = value;
                }
              }
            }
          }
          CogTile.pack(outputPixels, block, bands, dataType, raw);
          storeBuffer.reset();
          int length;
          try (var stream = storeBuffer.open()) {
            length = storeCodec.encode(stream, raw, block, block, stride);
          }
          store.append(storeBuffer.data(), length);
          if (jpeg != null) {
            outputBuffer.reset();
            int outputLength;
            try (var stream = outputBuffer.open()) {
              outputLength = jpeg.encode(stream, raw, block, block, stride);
            }
            output.append(outputBuffer.data(), outputLength);
          }
          tileCompleted.run();
        }
      }
      return new Level(store, output);
    } catch (Exception e) {
      store.close();
      if (output != store) output.close();
      throw e;
    }
  }

  private static double invalid(Double noData, int dataType) {
    if (noData != null) return noData;
    if (dataType == DataBuffer.TYPE_FLOAT || dataType == DataBuffer.TYPE_DOUBLE) return Double.NaN;
    return 0;
  }

  static List<int[]> planLevels(int width, int height, int block) {
    List<int[]> levels = new ArrayList<>();
    int w = width, h = height;
    while (w > block || h > block) {
      w = Math.max(1, (w + 1) / 2);
      h = Math.max(1, (h + 1) / 2);
      levels.add(new int[] {w, h});
    }
    return levels;
  }

  static void checkStopped(BooleanSupplier stopped) throws IOException {
    if (stopped != null && stopped.getAsBoolean()) throw new IOException("Raster write stopped");
  }
}
