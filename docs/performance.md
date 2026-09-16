# Performance acceptance

The frozen reference is Vector/Raster commit `4d2a81c7305c7618eb49359739cca8500a00e0aa`.
Build it in an isolated checkout before installing the new Raster Maven artifacts. Keep its ZIP,
source revision and checksum. Use the same unmodified Hop 2.19.0 distribution and Geometry 0.2
ZIP for both installed environments. The candidate additionally installs Raster Type.

`BenchmarkFixtures` in the adapter test sources creates deterministic tiled DEM/RGB fixtures
without materializing the entire source image. It accepts output directory and image dimension.
Use 2048 for initial comparisons and increase beyond the fixed JVM heap for scalability checks.

```sh
python3 scripts/create-benchmark-matrix.py \
  --baseline-hop /path/to/reference/hop --candidate-hop /path/to/candidate/hop \
  --fixtures /path/to/fixtures --output /path/to/matrix --java-home /path/to/jdk21 \
  --raster-zip /path/to/raster.zip --vector-zip /path/to/vector.zip
python3 scripts/check-pipeline-matrix.py /path/to/matrix/matrix.json --output /path/to/functional
python3 scripts/benchmark.py /path/to/matrix/matrix.json --output /path/to/results --pairs 10 \
  --functional /path/to/functional/report.json
```

The runner performs two warmups and at least ten paired runs, alternating order. It records full
Hop-process wall time, peak RSS, JVM-observed heap and GC pauses, controlled HTTP request/byte counts
and retained output-file bytes. Output bytes are **not** a measurement of every filesystem write
or transient allocation. Process runs start with cold application caches; repeated zones inside
one pipeline exercise warm source caches. OS caches are not flushed; use the same machine and avoid
concurrent builds or other heavy workloads.

Acceptance policy **version 2** uses the deterministic 95% bootstrap interval of paired median
candidate/reference ratios, separately for runtime and peak RSS. Upper bound <= 1.05 passes;
lower bound > 1.05 is a regression; otherwise the metric remains open. Both metrics must pass.
This explicitly replaces V1's original zero-tolerance rule. There is no absolute allowance or
outlier removal. At least 10 pairs are required, with open cases extended to 30 total; remaining
uncertainty is not success. Functional comparison must pass before interpreting speed results.
A faster result elsewhere never compensates for a failed case.

The generated 17-case matrix covers local/HTTP DEM and RGB, small/large clips, nearest/bilinear,
clip/reproject chains and repeated zones. It also covers CRS changes, strong
resampling, polygon holes, changing sources, fan-out and longer chains. Palette/multiband
scaling is a separate acceptance requirement. Unit tests cover those semantics independently of timing. Preserve result reports with the
exact candidate artifacts. A universal performance guarantee is not inferred from finite samples.

## Reference runtime limitation found during initial acceptance

The frozen installed reference fails on a DEM containing GDAL Scale/Offset XML metadata:
JAXB discovery runs with the wrong context classloader. This is a baseline execution failure,
not a valid timing sample. Timing fixtures use identity scale/offset; scaled DEM remains a
separate functional integration case. V1 scopes the GeoTIFF metadata/read context classloader
and successfully reads that fixture without changing JVM-wide JAXB settings.

## Publication gate

CI publishes only when `acceptance/release.json` supplies completed functional, installed-platform,
fixed-heap, multiband and temporary-I/O acceptance, the full named performance matrix (10–30 pairs
per case), and the SHA-256 of the exact canonical Raster and Vector/Raster ZIPs. The gate is implemented in
`scripts/check-release-acceptance.py`. Missing, open or stale evidence keeps publication disabled;
ordinary verification and installed tests still run. No release evidence is fabricated from unit
results or the initial subset of benchmarks. Matching Vector/Raster artifacts must be preserved
with that acceptance run and referenced in its report.

## Functional matrix and extending measurements

Run `python3 scripts/check-pipeline-matrix.py matrix.json --output functional-results` before timing.
It executes each reference/candidate pair once against the controlled Range server and checks all
materialized TIFF samples and metadata exactly using `BenchmarkCompare`. Statistics scenarios also
require their integration/unit numerical assertions (pipeline exit alone is insufficient).

To add samples to inconclusive cases without discarding the original ten pairs:
`python3 scripts/benchmark.py matrix.json --output results --pairs 30 --extend-open --functional functional-results/report.json`.
Reports fingerprint the installed Raster, Vector/Raster, Geometry and Hop core JARs; extending
a run rejects changed artifacts. This preserves completed pass/regression cases and extends only open cases to 30 total pairs.
All runs must use the same artifacts and machine conditions; otherwise begin a separately labelled run.

## Large files and real COGs

`run-scaling.py --hop HOP --java-home JDK --output DIR --raster-zip RASTER_ZIP --vector-zip VECTOR_ZIP` generates 16384 and 32768 square DEM/RGB
fixtures, runs 100/1000/10000 different clips, and writes/compares the full raster at `-Xmx512m`.
Generation and comparisons are blockwise; fixture generation uses a bounded 16 MiB tile cache.
Allow several GiB of disk and substantial runtime. No production cache limit is increased.

`run-public-cogs.py --hop HOP --baseline-hop REFERENCE_HOP --java-home JDK --output DIR --raster-zip RASTER_ZIP --vector-zip VECTOR_ZIP`
uses the two Solothurn URLs through a non-caching, 1 GiB-per-source Range proxy. `--source rgb`
or `--source dsm` selects a source. Reports include validators and traffic counts. No strong
snapshot guarantee is claimed without a strong ETag. The independent reference driver compiles
against frozen baseline JARs and compares band 1 of bounded clip/reproject outputs. Candidate
all-band output additionally exercises the new multiband path, without claiming old multiband
clip parity. These are functional network probes, never release timing samples.

`WriteVolumeProbe OUTPUT_DIR` (adapter test class, installed candidate classpath) enables JFR
FileWrite with threshold zero, calibrates the actual TIFF writer, executes a chain and reports
Java write bytes by TIFF path. This measures Java writes, not physical device I/O. The probe
must see calibration and neighboring writer temp-file events, no unexpected TIFF writes, and
no remaining writer temp files. The instrumented probe also injects a read failure and cancellation after writing starts, and
checks that neither targets nor temporary outputs remain. `run-write-volume.py` wraps it and
produces the versioned report; it takes the same Hop/JDK/output/ZIP arguments as the scaling runner.

## Evidence format

Release evidence schema 2 identifies the two canonical ZIP hashes, policy 2 and hashed report
references under `reports`. Required reports: `functional_matrix`, `performance`,
`installed_os_java_matrix`, `fixed_heap_scaling`, `multi_band_scaling`,
`temporary_io_measurements`, `public_cogs`, `resource_stress`, `large_chain`. Every report must identify the exact ZIPs under
`manifest.zip_hashes`. The gate recomputes performance status from samples and verifies its
functional report binding. Suite reports require named measured checks (`actual`, `expected`)
and completeness; summary strings alone never pass. Probe output is diagnostic evidence and
must not be promoted to complete release evidence while required cases are absent.

Script regression tests: `python3 -m unittest discover -s scripts -p 'test_*.py'`.

`run-multiband.py` uses the same arguments and generates RGBA, palette and numeric four-band
fixtures at 4096 and 16384 pixels per side. It checks polygon holes, exact unmasked samples,
band order, palette preservation and cache bounds. It is separate from baseline speed parity.

The scalability runner accepts `--fixture-root DIR` to reuse fixtures after checking their
streaming SHA-256 sidecars. All outputs remain in the run-specific output directory.
Each suite verifies that installed runtime JARs actually match the supplied ZIPs.

For coordinated CI before merging both repositories, manually dispatch `CI` on the Raster
branch with `companion_ref` set to the Vector/Raster commit. The companion job resolves this
to a SHA and all installed-test jobs use that same revision. Workflow/helper pins are unchanged.
Download the canonical CI ZIPs and use those exact files for publication acceptance; local ZIP
results cannot be transferred to newly rebuilt CI archives with different hashes.

`run-resource-stress.py --hop HOP --java-home JDK --matrix MATRIX_JSON --output DIR
--raster-zip RASTER_ZIP --vector-zip VECTOR_ZIP` checks 100/1000/10000 distinct zones
against the deterministic DEM formula, including cache bounds and reuse of the same reader.
It also runs 1000 distinct zones through installed Hop with one, two and four statistics
transform copies and compares complete result multisets. Run it outside benchmark timing.
Supply its report to the collector with `--resource-stress DIR/report.json`.

`run-large-chain.py` takes the same Hop/JDK/output/ZIP arguments plus `--fixture-root DIR`.
It clips half the width and height of each 16384/32768 DEM/RGB fixture, resamples it and
writes the complete result with `-Xmx512m`. Independent nearest-neighbour checks include
tile boundaries. Supply this report with `--large-chain DIR/report.json`.
