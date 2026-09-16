#!/usr/bin/env python3
"""Merge installed-test reports downloaded from the six CI cells without changing their hashes."""
import argparse,json
from pathlib import Path
from acceptance import sha256
p=argparse.ArgumentParser(description=__doc__);p.add_argument('reports',nargs='+',type=Path);p.add_argument('--output',required=True,type=Path);a=p.parse_args()
checks={};hashes=None;sources=[]
for path in a.reports:
    data=json.loads(path.read_text());identity=data['manifest']['zip_hashes']
    sources.append({'path':str(path.resolve()),'sha256':sha256(path),'manifest':data['manifest']})
    if hashes is not None and identity!=hashes:raise ValueError('Different artifacts across platform reports')
    hashes=identity
    for check in data['checks']:
        if check['name'] in checks:raise ValueError('Duplicate platform cell')
        checks[check['name']]=check
expected={os+'-java'+jdk for os in ('linux','macos','windows') for jdk in ('21','25')}
complete=set(checks)==expected and all(c['actual'] is True and c['expected'] is True for c in checks.values())
a.output.parent.mkdir(parents=True,exist_ok=True)
a.output.write_text(json.dumps({'manifest':{'zip_hashes':hashes},'reports':sources,'checks':list(checks.values()),'complete':complete,'missing':sorted(expected-checks.keys())},indent=2))
raise SystemExit(0 if complete else 1)
