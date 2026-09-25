package ch.so.agi.hop.raster.geotools;

/** Tile codec for COG output: compression tag, name and whether its blocks are lossless. */
interface CogCodec extends CogTileEncoder, AutoCloseable {
  int tag();

  String name();

  boolean lossless();
}
