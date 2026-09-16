#!/usr/bin/env python3
"""Distinct zones and installed Hop transform copies, separate from performance timings."""
import argparse
import csv
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as E
from acceptance import artifact_arguments, suite_manifest, sha256

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--hop', type=Path, required=True)
p.add_argument('--java-home', type=Path, required=True)
p.add_argument('--matrix', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
artifact_arguments(p)
a = p.parse_args()
a.output = a.output.resolve()
a.output.mkdir(parents=True, exist_ok=True)
root = Path(__file__).resolve().parents[1]
matrix = json.loads(a.matrix.read_text())
fixture = Path(matrix['http_root']) / 'dem.tif'
report = {'manifest': suite_manifest(a), 'complete': False, 'checks': [],
          'fixture_sha256': sha256(fixture)}
cp = os.pathsep.join([str(root/'hop-raster-geotools/target/test-classes')] +
                    [str(f.resolve()) for f in sorted((a.hop/'plugins').rglob('*.jar'))])

def run(name, command, env=None, cwd=None):
    with (a.output/(name+'.log')).open('w') as log:
        result = subprocess.run(command, env=env, cwd=cwd, stdout=log,
                                stderr=subprocess.STDOUT, timeout=600)
    report['checks'].append({'name': name, 'actual': result.returncode, 'expected': 0})
    (a.output/'report.json').write_text(json.dumps(report, indent=2))
    if result.returncode:
        raise RuntimeError(name+' failed')

for count in (100, 1000, 10000):
    run('zones-'+str(count), [str(a.java_home/'bin/java'), '-Xmx512m', '-cp', cp,
        'ch.so.agi.hop.raster.geotools.ZoneStressProbe', str(fixture), str(count)])

spec = next(c for c in matrix['cases'] if c['name'] == 'dem-zones')['candidate']
template = E.parse(spec['command'][-1])
baseline = None
for copies in (1, 2, 4):
    pipeline = E.fromstring(E.tostring(template.getroot()))
    rows = next(t for t in pipeline.findall('transform') if t.findtext('name') == 'rows')
    rows.find('type').text = 'DataGrid'
    for child in list(rows):
        if child.tag not in ('name', 'type', 'copies', 'distribute'):
            rows.remove(child)
    fields = E.SubElement(rows, 'fields')
    field = E.SubElement(fields, 'field')
    E.SubElement(field, 'name').text = 'geometry'
    E.SubElement(field, 'type').text = 'Geometry'
    data = E.SubElement(rows, 'data')
    for i in range(1000):
        x, y = 2600000 + i % 100, 1200000 + i // 100
        geometry = f'POLYGON (({x} {y},{x+4} {y},{x+4} {y+4},{x} {y+4},{x} {y}))'
        E.SubElement(E.SubElement(data, 'line'), 'item').text = geometry
    statistics = next(t for t in pipeline.findall('transform') if t.findtext('name') == 'statistics')
    statistics.find('copies').text = str(copies)
    output = a.output/f'copies-{copies}.csv'
    sink = next(t for t in pipeline.findall('transform') if t.findtext('name') == 'results')
    sink.find('file/name').text = str(output)
    path = a.output/f'copies-{copies}.hpl'
    E.ElementTree(pipeline).write(path, encoding='unicode')
    env = dict(os.environ, **matrix['env'])
    env['JAVA_HOME'] = str(a.java_home)
    run('copies-'+str(copies), [str(a.hop.resolve()/'hop-run.sh'), '-r', 'local', '-f', str(path)], env, a.hop)
    with output.open() as stream:
        values = sorted(tuple(row) for row in list(csv.reader(stream, delimiter=';'))[1:])
    if len(values) != 1000 or (baseline is not None and values != baseline):
        raise AssertionError('Transform copies produced missing or different results')
    baseline = values
    report['checks'].append({'name': 'copies-'+str(copies)+'-values', 'actual': len(values), 'expected': 1000})
report['complete'] = True
(a.output/'report.json').write_text(json.dumps(report, indent=2))
