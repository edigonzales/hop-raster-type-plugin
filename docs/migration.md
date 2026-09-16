# Migrating file-based raster pipelines

The old `SOGIS_RASTER_CLIP`, `SOGIS_RASTER_REPROJECT` and `SOGIS_RASTER_ZONAL_STATS`
IDs remain registered only to explain migration. They do not execute a parallel legacy engine.

The companion repository provides a non-destructive converter:

```sh
python3 scripts/migrate-raster-values.py old.hpl migrated.hpl
```

The output must be a new file. The converter inserts a Reader before each old operation, a Writer
after file-producing operations, and removes its temporary Raster column to retain old downstream
schemas. It preserves field bindings, variables, selected clip band, file destinations and error
routes. Review field-name collisions and the resulting layout before running a converted pipeline.
The generated helper field is named `__raster_value_N`; rename it if the upstream schema already
contains that field. Disconnected or unusual routing should be reviewed explicitly.

For a direct value chain, remove intermediate writers/readers and connect the Raster field:

```text
Before: Clip(input.tif → clip.tif) → Reproject(clip.tif → output.tif)
After:  Reader(input.tif → raster) → Clip(raster) → Reproject(raster) → Writer(output.tif)
```

Clip/Reproject replace their Raster input field by default. Set a different output Raster field to
retain both. Other input attributes remain unchanged. Writer adds `<prefix>output_file` and
`<prefix>status`; Zonal Statistics preserves its existing numeric result/status contracts.

Reader can produce a single row without input, or append a Raster field per input row. This allows
`Vector Reader → Raster Reader → Zonal Statistics` to reuse a source across all input zones. Remove
the Raster field with Select Values before sending rows to a vector writer that does not store it.

A pixel error in a lazy chain is handled by the Error Hop of Writer/Statistics. Attach error handling
to those consumers; successful planning in Clip/Reproject does not mean the source pixels were read.
