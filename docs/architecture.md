# Raster value contract

## Data and execution

`RasterDataset` contains a normalized source reference, its original descriptor and an ordered
list of operations with their output descriptors. All model collections are defensively copied.
`result()` returns the logical output descriptor. `RasterCodec` preserves source and operations
in a versioned format with a 16 MiB value limit and at most 128 steps; it never serializes pixels.
Comparisons and hashes describe references and operations, not pixel equality. Cloning is cheap.

A grid maps integer pixel centres to CRS coordinates with six affine coefficients. Public windows
start at zero and have exclusive upper bounds. Public reader band indexes start at zero; dialog
band numbers start at one. `RasterBand.dataType` currently uses Java DataBuffer codes 0–5
(Byte, UInt16, Int16, Int32, Float32, Float64). Reader tiles expose read-only DoubleBuffers;
the descriptor retains the actual logical storage type. Windows are limited to 262144 pixels.

`GeoToolsRasterBackend` is an explicit execution context, owned by one consumer transform copy.
It reuses a source reader and its 64 MiB cache across consecutive rows for the same source.
Open operation chains share 16 MiB output and 8 MiB operation caches. These limits are not a
whole-JVM memory guarantee: active windows, resampling coordinates, TIFF buffers and Hop rows
also consume memory. Source windows are recursively split for strongly downsampled outputs.

Metadata-only operation planning does not read pixels or reopen source files. Consumers recreate
operations in their original order. Pixel conversion and rounding at intermediate stages remain
part of the operation semantics; operations are not fused or reordered.

Each opened session must close. Closing a session releases its derived images; closing the context
also closes its source reader. A dataset remains reopenable afterwards. Contexts and sessions are
not shared between threads or pipeline branches. Branches share only immutable values.

## Source changes and transport

Local sources retain size, last-modified time and file identity. HTTP sources retain the available
range-response validator and length. Detected changes fail; missing strong validators cannot
provide an immutable remote snapshot. Public HTTP sources require valid partial-content responses;
full-download fallbacks and authenticated user-info URLs are rejected. Supply only public URLs.

Serialized local references require the same files to be available to the receiving process.
There is no automatic staging to another machine, embedded authentication or memory-raster transport.

## Semantic limits

V1 accepts affine GeoTIFF grids. Reprojection requires a CRS, strict transform availability,
explicit positive pixel sizes and an unambiguous target footprint. Nearest and bilinear use original
resolution; overviews are not selected automatically. Numeric interpolation excludes invalid
neighbours and retains scale/offset. NoData, alpha and color interpretation are separate concerns.

Clip defaults to all bands. Ordered explicit subsets become numeric unless they retain the complete
original color layout. Polygon RGB clips add alpha when needed; existing alpha is preserved/masked.
Palette masks require a representable NoData index. GeoTIFF output requires a common TIFF NoData
sentinel for numeric bands. Outputs retain DEFLATE, 512-pixel tiles and BigTIFF selection; they are
not advertised as newly generated COGs.

Only the writer materializes raster files. It uses a temporary sibling of the destination and
publishes it after success. It rejects writing to the original source, including hard-link aliases.
Parameter/metadata errors arise while planning; pixel errors arise at the consuming writer or
statistics transform, with source and operation context in the exception chain.

CRS parsing/formatting uses a bounded 32-entry metadata cache across transform copies. This cache
contains only CRS definitions, never readers or raster pixels; repeated metadata planning does not
reparse the same WKT for each row. A read-only tile may reference producer-owned cached storage,
which must remain immutable for the tile's lifetime.
