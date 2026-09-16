#!/usr/bin/env python3
"""Execute each baseline/candidate pipeline once, then compare materialized TIFFs exactly.

This is functional verification, not performance acceptance. Build adapter test classes first.
"""
import argparse,json,os,runpy,subprocess,csv
from acceptance import sha256
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('manifest',type=Path);p.add_argument('--output',required=True,type=Path)
a=p.parse_args();a.output=a.output.resolve();a.output.mkdir(parents=True,exist_ok=True)
m=json.loads(a.manifest.read_text());env=dict(os.environ,**m.get('env',{}))
helpers=runpy.run_path(str(Path(__file__).with_name('benchmark.py')))
report={'manifest':helpers['fingerprint'](m),'cases':[]}
server=helpers['start_fixture_server'](m,{'requests':0,'bytes':0})
root=Path(__file__).resolve().parents[1]
try:
    for case in m['cases']:
        for side in ('baseline','candidate'):
            s=case[side]
            with (a.output/(case['name']+'-'+side+'.log')).open('w') as log:
                subprocess.run(s['command'],cwd=s.get('cwd'),env=dict(env,**s.get('env',{})),stdout=log,stderr=subprocess.STDOUT,check=True,timeout=300)
        left=case['baseline'].get('outputs',[]);right=case['candidate'].get('outputs',[])
        if left and right and left[-1].endswith('.csv'):
            with open(left[-1]) as f: expected=list(csv.reader(f,delimiter=';'))
            with open(right[-1]) as f: actual=list(csv.reader(f,delimiter=';'))
            if len(expected)<2 or expected!=actual:raise AssertionError('Statistics values differ or are missing: '+case['name'])
            print(case['name']+': statistics values match',flush=True)
        elif left and right:
            home=Path(case['candidate']['cwd'])
            cp=os.pathsep.join([str(root/'hop-raster-geotools/target/test-classes')]+[str(p) for p in sorted((home/'plugins').rglob('*.jar'))])
            java=str(Path(env['JAVA_HOME'])/'bin/java')
            with (a.output/(case['name']+'-comparison.log')).open('w') as log:
                for expected_file,actual_file in zip(left[-len(right):],right):
                    subprocess.run([java,'-Xmx512m','-cp',cp,'ch.so.agi.hop.raster.geotools.BenchmarkCompare',expected_file,actual_file,str(case.get('comparison_relative_tolerance',0))],env=env,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=300)
            print(case['name']+': metadata/pixel comparison passed (relative tolerance '+str(case.get('comparison_relative_tolerance',0))+')',flush=True)
        else:
            raise AssertionError('No comparable outputs: '+case['name'])
        report['cases'].append({'name':case['name'],'status':'pass'})
        (a.output/'report.json').write_text(json.dumps(report,indent=2))
finally:
    if server:server.shutdown()
