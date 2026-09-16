#!/usr/bin/env python3
from pathlib import Path
from zipfile import ZipFile
from io import BytesIO
root=Path(__file__).resolve().parents[1]
files=list((root/'assemblies/target').glob('hop-raster-type-plugin-*.zip'))
assert len(files)==1, files
with ZipFile(files[0]) as z:
    jars=[n for n in z.namelist() if n.endswith('.jar')]
    assert len(jars)==2, jars
    assert any(n.startswith('plugins/misc/hop-raster-type/hop-raster-type-') for n in jars)
    assert any(n.startswith('plugins/misc/hop-raster-type/lib/hop-raster-core-') for n in jars)
    for n in jars:
        with ZipFile(BytesIO(z.read(n))) as jar:
            assert not any(c.startswith(('org/geotools/','org/apache/hop/')) for c in jar.namelist())
print('Raster Type package OK: only type and shared model, no backend runtime')
