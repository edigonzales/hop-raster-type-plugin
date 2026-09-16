#!/usr/bin/env python3
"""Run calibrated JFR raster write accounting separately from performance timings."""
import argparse,json,os,subprocess,re
from pathlib import Path
from acceptance import artifact_arguments,suite_manifest
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--hop',type=Path,required=True);p.add_argument('--java-home',type=Path,required=True);p.add_argument('--output',type=Path,required=True);artifact_arguments(p);a=p.parse_args()
a.output=a.output.resolve();a.output.mkdir(parents=True,exist_ok=True)
root=Path(__file__).resolve().parents[1]
cp=os.pathsep.join([str(root/'hop-raster-geotools/target/test-classes')]+[str(f.resolve()) for f in sorted((a.hop/'plugins').rglob('*.jar'))])
with (a.output/'probe.log').open('w') as log:
    result=subprocess.run([str(a.java_home/'bin/java'),'-Xmx512m','-cp',cp,'ch.so.agi.hop.raster.geotools.WriteVolumeProbe',str(a.output)],stdout=log,stderr=subprocess.STDOUT,timeout=300)
text=(a.output/'probe.log').read_text();success=result.returncode==0 and 'WRITE_VOLUME_PASS' in text
checks=[{'name':name,'actual':success and marker in text,'expected':True} for name,marker in [('calibration','WRITE_BYTES'),('chain-no-intermediate','WRITE_VOLUME_PASS'),('success-cleanup','WRITE_VOLUME_PASS'),('error-cleanup','ERROR_CLEANUP_PASS'),('abort-cleanup','ABORT_CLEANUP_PASS')]]
report={'manifest':suite_manifest(a),'checks':checks,'complete':all(c['actual']==c['expected'] for c in checks),'writes':[{'path':p,'bytes':int(n)} for n,p in re.findall(r'WRITE_BYTES (\d+) (.+)',text)]}
(a.output/'report.json').write_text(json.dumps(report,indent=2))
raise SystemExit(0 if report['complete'] else 1)
