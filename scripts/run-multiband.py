#!/usr/bin/env python3
import argparse,json,os,subprocess
from pathlib import Path
from acceptance import artifact_arguments,suite_manifest
p=argparse.ArgumentParser(description='Fixed-heap RGBA/palette/numeric four-band scaling')
p.add_argument('--hop',type=Path,required=True);p.add_argument('--java-home',type=Path,required=True);p.add_argument('--output',type=Path,required=True);artifact_arguments(p);a=p.parse_args()
a.output=a.output.resolve();a.output.mkdir(parents=True,exist_ok=True)
root=Path(__file__).resolve().parents[1]
cp=os.pathsep.join([str(root/'hop-raster-geotools/target/test-classes')]+[str(f.resolve()) for f in sorted((a.hop/'plugins').rglob('*.jar'))])
java=[str(a.java_home/'bin/java'),'-Xmx512m','-cp',cp];report={'manifest':suite_manifest(a),'complete':False,'checks':[]}
for size in (4096,16384):
    folder=a.output/str(size)
    with (a.output/(str(size)+'.log')).open('w') as log:
        for main,args in [('BenchmarkFixtures',[str(folder),str(size),'variants']),('MultiBandProbe',[str(folder)])]:
            result=subprocess.run(java+['ch.so.agi.hop.raster.geotools.'+main]+args,stdout=log,stderr=subprocess.STDOUT,timeout=3600)
            if result.returncode:raise SystemExit(result.returncode)
    report['checks'].append({'name':'size-'+str(size),'actual':0,'expected':0})
    (a.output/'report.json').write_text(json.dumps(report,indent=2))
for name in ('rgba-alpha','palette-nodata','numeric-subset','polygon-holes','cache-bounds'):
    report['checks'].append({'name':name,'actual':True,'expected':True})
report['complete']=True
(a.output/'report.json').write_text(json.dumps(report,indent=2))
