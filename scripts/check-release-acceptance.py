#!/usr/bin/env python3
"""Fail closed for publication when full V1 acceptance evidence is absent or stale.

The evidence file is produced after acceptance on the canonical (unrebuilt) ZIPs.
It is independent of ordinary build/test success, which must not imply V1 performance parity.
"""
import argparse
from acceptance import POLICY, evaluate, sha256
import json
from pathlib import Path

REQUIRED = {
    'dem-small-clip', 'dem-large-clip', 'dem-nearest', 'dem-bilinear',
    'dem-chain', 'dem-http-chain', 'rgb-bilinear', 'rgb-http-bilinear',
    'dem-zones', 'dem-http-zones', 'crs-change', 'strong-resampling',
    'polygon-holes', 'changing-sources', 'repeated-clips', 'fan-out', 'long-chain',
}

def check(evidence, artifact, vector_artifact):
    if not evidence.is_file():
        return False, 'Full V1 acceptance evidence has not been supplied'
    data=json.loads(evidence.read_text())
    if data.get('reference_commit') != '4d2a81c7305c7618eb49359739cca8500a00e0aa':
        return False, 'Incorrect reference revision'
    if data.get('raster_zip_sha256') != sha256(artifact):
        return False, 'Acceptance evidence does not identify the canonical Raster ZIP'
    if data.get('vector_zip_sha256') != sha256(vector_artifact):
        return False, 'Acceptance evidence does not identify the canonical Vector/Raster ZIP'
    if data.get('schema_version') != 2 or data.get('acceptance_policy') != POLICY:
        return False, 'Versioned 5-percent acceptance policy required'
    def read_report(key):
        reference=data.get('reports',{}).get(key,{})
        path=(evidence.parent/reference.get('path','')).resolve()
        if not path.is_file() or sha256(path)!=reference.get('sha256'):
            raise ValueError('Missing or changed report: '+key)
        report=json.loads(path.read_text())
        hashes=report.get('manifest',{}).get('zip_hashes',{})
        if hashes.get('raster')!=sha256(artifact) or hashes.get('vector')!=sha256(vector_artifact):
            raise ValueError('Report uses different artifacts: '+key)
        return report
    try:
        functional=read_report('functional_matrix')
        tested={c['name'] for c in functional.get('cases',[]) if c.get('status')=='pass'}
        if REQUIRED-tested: return False, 'Functional cases missing'
        performance=read_report('performance')
        if performance.get('invalid_reason'):return False, 'Invalidated performance report'
        manifest=performance.get('manifest',{})
        if manifest.get('acceptance_policy')!=POLICY:
            return False, 'Performance policy mismatch'
        functional_manifest=dict(manifest);functional_hash=functional_manifest.pop('functional_sha256',None)
        if functional_manifest!=functional['manifest'] or functional_hash!=data['reports']['functional_matrix']['sha256']:
            return False, 'Performance is not bound to functional evidence'
        cases={c['name']:c for c in performance.get('cases',[])}
        if REQUIRED-cases.keys(): return False, 'Performance cases missing'
        for name,case in cases.items():
            if evaluate(case['rows'])['status']!='pass': return False, 'Performance case is not accepted: '+name
        for requirement in ('installed_os_java_matrix','fixed_heap_scaling','multi_band_scaling','temporary_io_measurements','public_cogs','resource_stress','large_chain'):
            report=read_report(requirement)
            # A summary string alone can never satisfy a required suite.
            checks=report.get('checks',[])
            if not checks or any('actual' not in c or 'expected' not in c or c['actual']!=c['expected'] for c in checks):
                return False, 'Incomplete measured checks: '+requirement
            required_names={
                'installed_os_java_matrix':{os+'-java'+java for os in ('linux','macos','windows') for java in ('21','25')},
                'fixed_heap_scaling':{str(size)+'-'+kind+'-'+count for size in (16384,32768) for kind in ('dem','rgb') for count in ('100','1000','10000','full')},
                'multi_band_scaling':{'rgba-alpha','palette-nodata','numeric-subset','polygon-holes','cache-bounds'},
                'temporary_io_measurements':{'calibration','chain-no-intermediate','success-cleanup','error-cleanup','abort-cleanup'},
                'large_chain':{str(size)+'-'+kind+'-chain' for size in (16384,32768) for kind in ('dem','rgb')},
                'resource_stress':{'zones-100','zones-1000','zones-10000','copies-1-values','copies-2-values','copies-4-values'},
                'public_cogs':{'rgb-probe','dsm-probe','rgb-reference','dsm-reference','range-budget'}
            }[requirement]
            if required_names-{c.get('name') for c in checks}:return False, 'Required checks missing: '+requirement
            if report.get('complete') is not True: return False, 'Incomplete suite: '+requirement
    except (ValueError,KeyError,TypeError,OSError) as error:
        return False, str(error)
    return True, 'Complete V1 acceptance evidence matches the canonical artifact'

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--evidence',type=Path,required=True)
    p.add_argument('--artifact',type=Path,required=True)
    p.add_argument('--vector-artifact',type=Path,required=True)
    p.add_argument('--github-output',type=Path)
    a=p.parse_args()
    ready,reason=check(a.evidence,a.artifact,a.vector_artifact)
    print(reason)
    if a.github_output:
        with a.github_output.open('a') as out:out.write('ready='+str(ready).lower()+'\n')
    else:raise SystemExit(0 if ready else 1)
