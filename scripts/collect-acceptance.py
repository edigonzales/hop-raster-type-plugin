#!/usr/bin/env python3
"""Collect hashed existing reports; incomplete evidence remains non-publishable."""
import argparse,json,os,runpy
from pathlib import Path
from acceptance import POLICY,sha256
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--raster-zip',type=Path,required=True);p.add_argument('--vector-zip',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
keys=('functional_matrix','performance','installed_os_java_matrix','fixed_heap_scaling','multi_band_scaling','temporary_io_measurements','public_cogs','resource_stress','large_chain')
for key in keys:p.add_argument('--'+key.replace('_','-'),type=Path)
a=p.parse_args();a.output=a.output.resolve();a.output.parent.mkdir(parents=True,exist_ok=True)
data={'schema_version':2,'reference_commit':'4d2a81c7305c7618eb49359739cca8500a00e0aa','acceptance_policy':POLICY,'raster_zip_sha256':sha256(a.raster_zip),'vector_zip_sha256':sha256(a.vector_zip),'reports':{}}
for key in keys:
    report=getattr(a,key)
    if report is not None and report.is_file():
        data['reports'][key]={'path':os.path.relpath(report.resolve(),a.output.parent),'sha256':sha256(report)}
a.output.write_text(json.dumps(data,indent=2))
check=runpy.run_path(str(Path(__file__).with_name('check-release-acceptance.py')))['check']
ready,reason=check(a.output,a.raster_zip,a.vector_zip)
print(json.dumps({'ready':ready,'reason':reason}))
raise SystemExit(0 if ready else 1)
