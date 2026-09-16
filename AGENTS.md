# Repository instructions

Use JDK 21 for canonical builds, JDK 25 for compatibility. Read the shared CI contract
at https://github.com/edigonzales/hop-plugin-ci/blob/main/docs/ci-contract.md before CI changes.
Preserve the workflow/helper pins.

Build: `mvn -s "$MAVEN_SETTINGS" -B -ntp clean verify`, then `python3 scripts/verify-package.py`.
Use the shared `write_maven_settings.py` helper to prepare Maven repositories.
The companion Vector/Raster repository consumes this repository's Maven artifacts:
use `mvn -s "$MAVEN_SETTINGS" install` for coordinated local development.

The adapter's imported tests exercise the existing raster algorithms; retain them when refactoring.
Raster values must never own open readers or pixel arrays. Do not add GeoTools dependencies to core.
Installed integration is tested with the companion Vector/Raster ZIP, Geometry 0.2.0-SNAPSHOT ZIP,
and the Raster Type ZIP, using `scripts/run-installed-e2e.py` in the companion repository.
