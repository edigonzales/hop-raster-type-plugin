#!/usr/bin/env python3
"""Paired external-process benchmark. Manifest commands must perform equivalent complete pipelines.

Uses a fixed seeded bootstrap for paired runtime medians. Inconclusive comparisons stay open.
Peak RSS comes from OS process accounting, heap/GC from JVM logs supplied by the commands.
"""
import argparse
import json
import os
from pathlib import Path
import random
import statistics
import subprocess
import sys
import time
import re
import threading
from http.server import ThreadingHTTPServer,BaseHTTPRequestHandler
import hashlib
import platform
from acceptance import POLICY, sha256, evaluate, verify_installed_zip


def run(command,env,cwd,log):
    timing=log.with_suffix('.time')
    if sys.platform=='darwin':command=['/usr/bin/time','-l','-o',str(timing),*command]
    elif sys.platform.startswith('linux'):command=['/usr/bin/time','-v','-o',str(timing),*command]
    else:raise RuntimeError('Performance acceptance requires Linux or macOS process accounting')
    gc=log.with_suffix('.gc').resolve()
    env=dict(env)
    env['JAVA_TOOL_OPTIONS']=env.get('JAVA_TOOL_OPTIONS','')+' -Xlog:gc*,gc+heap=debug:file='+str(gc)
    started=time.perf_counter()
    with log.open('w') as out:subprocess.run(command,env=env,cwd=cwd,stdout=out,stderr=subprocess.STDOUT,check=True)
    elapsed=time.perf_counter()-started
    text=timing.read_text();rss=None
    for line in text.splitlines():
        if 'maximum resident set size' in line.lower():
            if sys.platform=='darwin':rss=int(line.strip().split()[0])
            else:rss=int(line.rsplit(':',1)[-1])*1024
    gc_text=gc.read_text() if gc.exists() else ''
    pauses=[float(v) for v in re.findall(r'Pause[^\n]*?([0-9.]+)ms',gc_text)]
    heaps=[int(v)*1024 for v in re.findall(r'total \d+K, used (\d+)K',gc_text)]
    return {'seconds':elapsed,'peak_rss_bytes':rss,'gc_pause_ms':sum(pauses),'gc_pause_count':len(pauses),'observed_heap_bytes':max(heaps,default=0)}


def start_fixture_server(manifest, counters):
    server=None
    if manifest.get('http_root'):
        root=Path(manifest['http_root']).resolve()
        validators={p.name:'"'+sha256(p)+'"' for p in root.glob('*.tif')}
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_GET(self):
                file=root/Path(self.path).name
                if not file.is_file():self.send_error(404);return
                match=re.fullmatch(r'bytes=(\d+)-(\d+)',self.headers.get('Range',''))
                if not match:self.send_error(400,'Range required');return
                start,end=map(int,match.groups());length=file.stat().st_size;end=min(end,length-1)
                if start>end:self.send_error(416);return
                self.send_response(206);self.send_header('Content-Range',f'bytes {start}-{end}/{length}');self.send_header('Content-Length',str(end-start+1));self.send_header('ETag',validators[file.name]);self.end_headers()
                counters['requests'] += 1
                with file.open('rb') as f:
                    f.seek(start)
                    remaining = end-start+1
                    while remaining:
                        data = f.read(min(1024*1024, remaining))
                        if not data: break
                        self.wfile.write(data)
                        counters['bytes'] += len(data)
                        remaining -= len(data)
        server=ThreadingHTTPServer(('127.0.0.1',manifest.get('http_port',18556)),Handler)
        threading.Thread(target=server.serve_forever,daemon=True).start()
    return server


def installed_artifacts(manifest):
    """Bind evidence to installed production JARs, including shared type/classloader dependencies."""
    homes={str(Path(case[side]['cwd']).resolve()) for case in manifest['cases'] for side in ('baseline','candidate')}
    result={}
    for location in sorted(homes):
        home=Path(location);files={}
        for folder in ('plugins/transforms/vector-raster','plugins/misc/hop-raster-type',
                       'plugins/misc/hop-geometry-type','lib/core'):
            root=home/folder
            for path in sorted(root.rglob('*')):
                if path.is_file() and (path.suffix=='.jar' or path.name=='dependencies.xml'):
                    files[str(path.relative_to(home))]=sha256(path)
        result[location]=files
    return result


def fingerprint(manifest):
    manifest=json.loads(json.dumps(manifest))
    manifest['installed_artifacts']=installed_artifacts(manifest)
    manifest['acceptance_policy']=POLICY
    manifest['host']={'system':platform.platform(), 'machine':platform.machine(), 'node':platform.node()}
    manifest['fixtures']={str(p.resolve()):sha256(p) for p in sorted(Path(manifest['http_root']).glob('*.tif'))} if manifest.get('http_root') else {}
    java=Path(manifest.get('env',{}).get('JAVA_HOME',os.environ.get('JAVA_HOME','')))/'bin/java'
    manifest['jdk']=subprocess.check_output([str(java),'-version'],stderr=subprocess.STDOUT,text=True)
    if set(manifest.get('artifacts',{}))!={'raster','vector'}:
        raise ValueError('Both candidate ZIPs must be identified')
    manifest['zip_hashes']={key:sha256(value) for key,value in manifest['artifacts'].items()}
    for home in {case['candidate']['cwd'] for case in manifest['cases']}:
        for archive in manifest['artifacts'].values():verify_installed_zip(home,archive)
    return manifest

def validate_extension(previous, manifest):
    if previous.get('manifest') != manifest:
        raise ValueError('Cannot combine different benchmark manifests')
    for case in previous.get('cases', []):
        if evaluate(case['rows'])['status'] != case['status']:
            raise ValueError('Previous measurement status does not match its samples')

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('manifest',type=Path);p.add_argument('--output',required=True,type=Path);p.add_argument('--pairs',type=int,default=10);p.add_argument('--functional',type=Path,required=True);p.add_argument('--extend-open',action='store_true',help='Extend existing inconclusive cases to --pairs total, preserving all original pairs');a=p.parse_args()
    if a.pairs<10 or a.pairs>30:p.error('Use 10–30 paired runs')
    a.output=a.output.resolve();a.output.mkdir(parents=True,exist_ok=True);manifest=json.loads(a.manifest.read_text());report={'manifest':manifest,'cases':[]}
    manifest = fingerprint(manifest)
    functional=json.loads(a.functional.read_text())
    if functional.get('manifest') != manifest or any(c.get('status')!='pass' for c in functional.get('cases',[])) or {c['name'] for c in functional.get('cases',[])} != {c['name'] for c in manifest['cases']}:
        raise ValueError('Matching successful functional evidence required before timing')
    manifest['functional_sha256']=sha256(a.functional)
    report['manifest']=manifest
    previous={}
    if a.extend_open:
        old=json.loads((a.output/'report.json').read_text())
        validate_extension(old, manifest)
        previous={case['name']:case for case in old['cases']}
        report=old
    server=None
    counters={'requests':0,'bytes':0}
    server=start_fixture_server(manifest,counters)
    for case in manifest['cases']:
        name=case['name'];prior=previous.get(name)
        if prior and (prior['status']!='open' or len(prior['rows'])>=a.pairs):continue
        rows=list(prior['rows']) if prior else []
        first=len(rows)
        env=dict(os.environ,**manifest.get('env',{}),**case.get('env',{}))
        for round in [ -2, -1, *range(first,a.pairs) ]:
            row={}
            for side in (['baseline','candidate'] if round%2==0 else ['candidate','baseline']):
                before=dict(counters)
                spec=case[side];row[side]=run(spec['command'],dict(env,**spec.get('env',{})),spec.get('cwd'),a.output/f'{name}-{round}-{side}.log')
                row[side]['http_requests']=counters['requests']-before['requests']
                row[side]['http_bytes']=counters['bytes']-before['bytes']
                row[side]['output_bytes']=sum(Path(f).stat().st_size for f in spec.get('outputs',[]) if Path(f).exists())
            if round>=0:rows.append(row)
        result=evaluate(rows)
        report['cases']=[result for result in report['cases'] if result['name']!=name]
        report['cases'].append({'name':name,'rows':rows,**result})
        (a.output/'report.json').write_text(json.dumps(report,indent=2))
        print(name,result['status'],result['runtime_ratio_median'],flush=True)
    if server:server.shutdown();server.server_close()
    final_manifest=fingerprint(json.loads(a.manifest.read_text()))
    final_manifest['functional_sha256']=sha256(a.functional)
    if final_manifest!=manifest:
        report['invalid_reason']='Inputs or artifacts changed during measurement'
        (a.output/'report.json').write_text(json.dumps(report,indent=2))
        raise ValueError(report['invalid_reason'])
    return 0 if all(c['status']=='pass' for c in report['cases']) else 1

if __name__=='__main__':raise SystemExit(main())
