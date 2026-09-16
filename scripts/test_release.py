import unittest,tempfile,runpy,json
from pathlib import Path
from acceptance import sha256,POLICY
check=runpy.run_path(str(Path(__file__).with_name('check-release-acceptance.py')))['check']
class ReleaseTest(unittest.TestCase):
    def test_summary_flags_are_not_evidence(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);artifact=root/'a.zip';artifact.write_bytes(b'artifact');evidence=root/'release.json'
            data={'reference_commit':'4d2a81c7305c7618eb49359739cca8500a00e0aa','raster_zip_sha256':sha256(artifact),'vector_zip_sha256':sha256(artifact),'schema_version':2,'acceptance_policy':POLICY,
                  'functional_matrix':'pass','installed_os_java_matrix':'pass','fixed_heap_scaling':'pass','multi_band_scaling':'pass','temporary_io_measurements':'pass'}
            evidence.write_text(json.dumps(data))
            self.assertFalse(check(evidence,artifact,artifact)[0])
            data['reports']={'functional_matrix':{'path':'fabricated.json','sha256':'bad'}}
            evidence.write_text(json.dumps(data))
            self.assertFalse(check(evidence,artifact,artifact)[0])
