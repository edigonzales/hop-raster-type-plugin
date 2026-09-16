#!/usr/bin/env python3
"""Large derived rasters under the same fixed heap and cache settings as normal execution."""
import argparse,json,os,subprocess
from pathlib import Path
from acceptance import artifact_arguments,suite_manifest,sha256
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--hop',type=Path,required=True);p.add_argument('--java-home',type=Path,required=True)
p.add_argument('--fixture-root',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
artifact_arguments(p);a=p.parse_args();a.output=a.output.resolve();a.output.mkdir(parents=True,exist_ok=True)
root=Path(__file__).resolve().parents[1]
cp=os.pathsep.join([str(root/'hop-raster-geotools/target/test-classes')]+[str(f.resolve()) for f in sorted((a.hop/'plugins').rglob('*.jar'))])
report={'manifest':suite_manifest(a),'complete':False,'checks':[],'fixtures':{}}
for size in (16384,32768):
    for kind in ('dem','rgb'):
        name=f'{size}-{kind}-chain';source=a.fixture_root/str(size)/(kind+'.tif')
        report['fixtures'][name]=sha256(source)
        with (a.output/(name+'.log')).open('w') as log:
            result=subprocess.run([str(a.java_home/'bin/java'),'-Xmx512m','-cp',cp,
                'ch.so.agi.hop.raster.geotools.LargeChainProbe',str(source),str(a.output/(name+'.tif'))],
                stdout=log,stderr=subprocess.STDOUT,timeout=3600)
        report['checks'].append({'name':name,'actual':result.returncode,'expected':0})
        (a.output/'report.json').write_text(json.dumps(report,indent=2))
        if result.returncode:raise RuntimeError(name+' failed')
        print(name+' passed',flush=True)
report['complete']=True
(a.output/'report.json').write_text(json.dumps(report,indent=2))
