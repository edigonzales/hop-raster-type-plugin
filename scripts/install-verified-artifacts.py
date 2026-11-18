#!/usr/bin/env python3
"""Install the exact staged Maven files from a downloaded canonical artifact."""

import argparse
from pathlib import Path
import subprocess


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("bundle", type=Path)
parser.add_argument("--settings", required=True)
args = parser.parse_args()

parent_artifact = "hop-raster-type-plugin-parent"
parents = sorted(args.bundle.glob(f"{parent_artifact}-*.pom"))
if len(parents) != 1:
    parser.error(f"Expected one {parent_artifact} POM in {args.bundle}, found {parents}")
version = parents[0].stem.removeprefix(f"{parent_artifact}-")

for artifact in (parent_artifact, "hop-raster-core", "hop-raster-type", "hop-raster-geotools"):
    pom = args.bundle / f"{artifact}-{version}.pom"
    file = pom if artifact == parent_artifact else pom.with_suffix(".jar")
    subprocess.run(
        [
            "mvn", "-s", args.settings, "-B", "-ntp", "install:install-file",
            f"-Dfile={file.resolve()}", f"-DpomFile={pom.resolve()}", "-DgeneratePom=false",
        ],
        check=True,
    )
