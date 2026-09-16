import unittest
import tempfile
from pathlib import Path
from acceptance import classify, evaluate, sha256, verify_installed_zip, compare_statistics

class AcceptanceTest(unittest.TestCase):
    def test_boundary(self):
        self.assertEqual(classify((1, 1.05)), 'pass')
        self.assertEqual(classify((1.05, 1.06)), 'open')
        self.assertEqual(classify((1.050001, 1.06)), 'regression')
    def rows(self):
        return [{'baseline':{'seconds':1,'peak_rss_bytes':100},'candidate':{'seconds':1.04,'peak_rss_bytes':104}} for _ in range(10)]
    def test_metrics_separate(self):
        rows=self.rows()
        self.assertEqual(evaluate(rows)['status'],'pass')
        for r in rows:r['candidate']['peak_rss_bytes']=106
        result=evaluate(rows)
        self.assertEqual(result['runtime_status'],'pass')
        self.assertEqual(result['rss_status'],'regression')
        self.assertEqual(result['status'],'regression')
    def test_missing_invalid(self):
        for value in (None,0,float('nan'),float('inf'),True):
            rows=self.rows();rows[0]['candidate']['seconds']=value
            with self.assertRaises(ValueError):evaluate(rows)
        with self.assertRaises(ValueError):evaluate(self.rows()[:9])
    def test_hash_streaming(self):
        import hashlib
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'file';p.write_bytes(b'x'*2000001)
            self.assertEqual(sha256(p),hashlib.sha256(p.read_bytes()).hexdigest())
    def test_installation_must_match_zip(self):
        import zipfile
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);archive=root/'plugin.zip';jar=root/'plugins/test/lib.jar';jar.parent.mkdir(parents=True);jar.write_bytes(b'original')
            with zipfile.ZipFile(archive,'w') as z:z.writestr('plugins/test/lib.jar',b'original')
            verify_installed_zip(root,archive)
            jar.write_bytes(b'changed')
            with self.assertRaises(ValueError):verify_installed_zip(root,archive)
    def test_statistics_rounding_and_real_changes(self):
        row='STATS Result[count=2, mean=100.0, min=50.0, max=150.0, sum=200.0, stddev=50.0, status=OK]'
        self.assertTrue(compare_statistics(row,row.replace('mean=100.0','mean=100.00000000001')))
        self.assertFalse(compare_statistics(row,row.replace('count=2','count=3')))
        self.assertFalse(compare_statistics(row,row.replace('mean=100.0','mean=100.01')))
        self.assertFalse(compare_statistics(row,''))
if __name__=='__main__':unittest.main()
