#!/usr/bin/env python3
"""Install the exact staged Maven files from a downloaded canonical verification bundle."""
from pathlib import Path
import argparse
import subprocess
p=argparse.ArgumentParser();p.add_argument('bundle',type=Path);p.add_argument('--settings',required=True);a=p.parse_args()
for module in [None,'hop-raster-core','hop-raster-type','hop-raster-geotools']:
    pom=a.bundle/('pom.xml' if module is None else module+'.pom')
    file=pom if module is None else a.bundle/(module+'-0.1.0-SNAPSHOT.jar')
    subprocess.run(['mvn','-s',a.settings,'-B','-ntp','install:install-file',f'-Dfile={file.resolve()}',f'-DpomFile={pom.resolve()}','-DgeneratePom=false'],check=True)
