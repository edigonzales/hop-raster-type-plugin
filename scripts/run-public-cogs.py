#!/usr/bin/env python3
"""Explicit bounded public-source probe; never used as a speed acceptance benchmark."""
import argparse,json,os,subprocess,re
from pathlib import Path
from range_proxy import RangeProxy
from acceptance import artifact_arguments, suite_manifest, compare_statistics, sha256

URLS={
'rgb':'https://files.geo.so.ch/ch.swisstopo.swissimage_2024.rgb/aktuell/ch.swisstopo.swissimage_2024.rgb.tif',
'dsm':'https://files.geo.so.ch/ch.swisstopo.lidar_2023.dsm/aktuell/ch.swisstopo.lidar_2023.dsm.tif'}
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--baseline-hop',type=Path,required=True);p.add_argument('--hop',type=Path,required=True);p.add_argument('--java-home',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--source',choices=['rgb','dsm','all'],default='all');artifact_arguments(p);a=p.parse_args()
manifest=suite_manifest(a)
root=Path(__file__).resolve().parents[1];a.output.mkdir(parents=True,exist_ok=True)
cp=os.pathsep.join([str(root/'hop-raster-geotools/target/test-classes')]+[str(f.resolve()) for f in sorted((a.hop/'plugins').rglob('*.jar'))])
baseline_cp=os.pathsep.join(str(f.resolve()) for f in sorted((a.baseline_hop/'plugins').rglob('*.jar')))
classes=a.output/'reference-classes';classes.mkdir(exist_ok=True)
subprocess.run([str(a.java_home/'bin/javac'),'-proc:none','-cp',baseline_cp,'-d',str(classes),str(root/'scripts/fixtures/ReferenceCogProbe.java')],check=True)
reports=[]
for name,url in URLS.items():
    if a.source not in ('all',name):continue
    with RangeProxy(url) as proxy:
        folder=a.output/name;folder.mkdir(exist_ok=True)
        with (folder/'probe.log').open('w') as log:
            try:
                result=subprocess.run([str(a.java_home/'bin/java'),'-Xmx512m','-cp',cp,'ch.so.agi.hop.raster.geotools.AcceptanceProbe',proxy.address,str(folder.resolve()),'100'],stdout=log,stderr=subprocess.STDOUT,timeout=600)
                code=result.returncode
            except subprocess.TimeoutExpired:code=124
        record=proxy.report();record['exit_code']=code;record['name']=name
        record['probe_pass']=code==0 and not record['failure'] and 'PROBE_PASS' in (folder/'probe.log').read_text()
        record['reference_comparison']='open'
        selected=re.search(r'SELECTED (\d+) (\d+)',(folder/'probe.log').read_text())
        if record['probe_pass'] and selected:
            reference=folder/'reference';reference.mkdir(exist_ok=True)
            with (folder/'reference.log').open('w') as log:
                ref=subprocess.run([str(a.java_home/'bin/java'),'-Xmx512m','-cp',str(classes.resolve())+os.pathsep+baseline_cp,'ReferenceCogProbe',proxy.address,str(reference.resolve()),*selected.groups()],stdout=log,stderr=subprocess.STDOUT,timeout=600)
            if ref.returncode==0:
                with (folder/'comparison.log').open('w') as log:
                    codes=[subprocess.run([str(a.java_home/'bin/java'),'-Xmx512m','-cp',cp,'ch.so.agi.hop.raster.geotools.BenchmarkCompare',str(reference/('band1-'+str(size)+'.tif')),str(folder/('band1-'+str(size)+'.tif'))],stdout=log,stderr=subprocess.STDOUT,timeout=120).returncode for size in (128,1024)]
                record['reference_comparison']='pass' if codes==[0,0] else 'fail'
            else:record['reference_comparison']='reference-failure'
        record.update(proxy.report())
        record['window']=list(map(int,selected.groups())) if selected else None
        if name=='dsm' and record['reference_comparison']=='pass':
            if not compare_statistics((folder/'reference.log').read_text(),(folder/'probe.log').read_text()):record['reference_comparison']='statistics-mismatch'
        record['log_hashes']={p.name:sha256(p) for p in folder.glob('*.log')}
        reports.append(record)
        checks=[{'name':r['name']+'-probe','actual':r['probe_pass'] and not r['failure'],'expected':True} for r in reports]
        checks += [{'name':r['name']+'-reference','actual':r['reference_comparison'],'expected':'pass'} for r in reports]
        checks += [{'name':'range-budget','actual':all(r['bytes']<=r['budget'] and not r['failure'] for r in reports),'expected':True}]
        complete=len(reports)==2 and all(c['actual']==c['expected'] for c in checks)
        (a.output/'report.json').write_text(json.dumps({'manifest':manifest,'complete':complete,'checks':checks,'sources':reports},indent=2))
        print(name,record,flush=True)
raise SystemExit(0 if all(r['probe_pass'] and not r['failure'] and r['reference_comparison']=='pass' for r in reports) else 1)
