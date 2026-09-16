"""Versioned, shared acceptance rules. No caller-supplied success flag is trusted."""
import hashlib
import math
import random
import statistics

POLICY = {'version': 2, 'ratio_limit': 1.05, 'confidence': 0.95,
          'bootstrap_samples': 10000, 'seed': 2056, 'min_pairs': 10, 'max_pairs': 30}

def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()

def interval(values):
    rng = random.Random(POLICY['seed'])
    samples = sorted(statistics.median(rng.choices(values, k=len(values)))
                     for _ in range(POLICY['bootstrap_samples']))
    return samples[250], samples[9749]

def classify(bounds):
    lo, hi = bounds
    if not all(math.isfinite(x) for x in bounds) or lo <= 0 or lo > hi:
        raise ValueError('Invalid confidence interval')
    return 'pass' if hi <= POLICY['ratio_limit'] else 'regression' if lo > POLICY['ratio_limit'] else 'open'

def evaluate(rows):
    if not POLICY['min_pairs'] <= len(rows) <= POLICY['max_pairs']:
        raise ValueError('Acceptance requires 10–30 pairs')
    result = {}
    for label, key in [('runtime', 'seconds'), ('rss', 'peak_rss_bytes')]:
        ratios = []
        for row in rows:
            values = [row[side][key] for side in ('baseline', 'candidate')]
            if any(isinstance(v, bool) or not isinstance(v, (float, int)) or not math.isfinite(v) or v <= 0 for v in values):
                raise ValueError('Missing or invalid ' + key)
            ratios.append(values[1] / values[0])
        bounds = interval(ratios)
        result[label + '_ratio_median'] = statistics.median(ratios)
        result[label + '_ratio_ci95'] = bounds
        result[label + '_status'] = classify(bounds)
    statuses = [result[k + '_status'] for k in ('runtime', 'rss')]
    result['status'] = 'regression' if 'regression' in statuses else 'open' if 'open' in statuses else 'pass'
    return result

def artifact_arguments(parser):
    from pathlib import Path
    parser.add_argument('--raster-zip', type=Path, required=True)
    parser.add_argument('--vector-zip', type=Path, required=True)

def suite_manifest(args):
    import platform
    import subprocess
    for archive in (args.raster_zip,args.vector_zip):verify_installed_zip(args.hop,archive)
    return {'acceptance_policy': POLICY,
            'zip_hashes': {'raster': sha256(args.raster_zip), 'vector': sha256(args.vector_zip)},
            'host': platform.platform(), 'heap': '-Xmx512m',
            'jdk': subprocess.check_output([str(args.java_home/'bin/java'), '-version'],stderr=subprocess.STDOUT,text=True)}

def verify_installed_zip(home, archive):
    import zipfile
    from pathlib import Path
    checked=0
    with zipfile.ZipFile(archive) as bundle:
        for entry in bundle.infolist():
            if not (entry.filename.endswith('.jar') or entry.filename.endswith('/dependencies.xml')):
                continue
            digest=hashlib.sha256()
            with bundle.open(entry) as stream:
                for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
            installed=Path(home)/entry.filename
            if not installed.is_file() or sha256(installed)!=digest.hexdigest():
                raise ValueError('Installed artifact does not match ZIP: '+str(installed))
            checked+=1
    if not checked:raise ValueError('ZIP contains no runtime artifacts')

def compare_statistics(expected, actual):
    """Public floating-point stats: exact counts/extrema/status; agreed 1e-6 bound otherwise."""
    import re
    def parse(text):
        rows=[]
        for body in re.findall(r'STATS Result\[([^\]]+)\]',text):
            rows.append(dict(item.split('=',1) for item in body.split(', ')))
        return rows
    left,right=parse(expected),parse(actual)
    if not left or len(left)!=len(right):return False
    keys={'count','mean','min','max','sum','stddev','status'}
    for a,b in zip(left,right):
        if set(a)!=keys or set(b)!=keys:return False
        for key in keys:
            if key in ('count','status','min','max') or a[key]=='null' or b[key]=='null':
                if a[key]!=b[key]:return False
            else:
                x,y=float(a[key]),float(b[key])
                if not math.isfinite(x) or not math.isfinite(y) or abs(x-y)>1e-6*max(1,abs(x)):return False
    return True
