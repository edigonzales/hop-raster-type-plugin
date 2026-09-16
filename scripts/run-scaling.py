#!/usr/bin/env python3
"""Deterministic large-file suite. All Java invocations use a fixed 512 MiB heap."""
import argparse,json,os,subprocess
from pathlib import Path
from acceptance import artifact_arguments,suite_manifest,sha256
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--hop',type=Path,required=True);p.add_argument('--java-home',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
artifact_arguments(p);p.add_argument('--fixture-root',type=Path);a=p.parse_args();a.output=a.output.resolve();a.output.mkdir(parents=True,exist_ok=True)
root=Path(__file__).resolve().parents[1]
cp=os.pathsep.join([str(root/'hop-raster-geotools/target/test-classes')]+[str(f.resolve()) for f in sorted((a.hop/'plugins').rglob('*.jar'))])
java=[str(a.java_home/'bin/java'),'-Xmx512m','-cp',cp];report={'manifest':suite_manifest(a),'complete':False,'checks':[]}
def run(name,main,args,timeout=3600):
    with (a.output/(name+'.log')).open('w') as log:
        result=subprocess.run(java+['ch.so.agi.hop.raster.geotools.'+main]+list(map(str,args)),stdout=log,stderr=subprocess.STDOUT,timeout=timeout)
    report['checks'].append({'name':name,'actual':result.returncode,'expected':0})
    (a.output/'report.json').write_text(json.dumps(report,indent=2))
    if result.returncode:raise RuntimeError(name+' failed')
    print(name+' passed',flush=True)
for size in (16384,32768):
    folder=(a.fixture_root or a.output)/str(size)
    identity=folder/'fixture-sha256.json'
    if identity.is_file():
        hashes=json.loads(identity.read_text())
        if any(sha256(folder/name)!=value for name,value in hashes.items()):raise RuntimeError('Fixture changed')
    else:
        run('fixtures-'+str(size),'BenchmarkFixtures',[folder,size])
        identity.write_text(json.dumps({name:sha256(folder/name) for name in ('dem.tif','rgb.tif')}))
    report.setdefault('fixtures',{})[str(size)]=json.loads(identity.read_text())
    for kind in ('dem','rgb'):
        for count in (100,1000,10000):
            run(f'{size}-{kind}-{count}','AcceptanceProbe',[folder/(kind+'.tif'),a.output/(str(size)+'-'+kind+'-outputs'),count])
        run(f'{size}-{kind}-full','FullRasterProbe',[folder/(kind+'.tif'),a.output/(str(size)+'-'+kind+'-full.tif')])
report['complete']=True
(a.output/'report.json').write_text(json.dumps(report,indent=2))
