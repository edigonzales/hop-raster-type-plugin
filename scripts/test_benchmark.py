import copy
import json
import tempfile
import unittest
import urllib.request
from pathlib import Path
from benchmark import start_fixture_server, validate_extension
from acceptance import POLICY

class BenchmarkTest(unittest.TestCase):
    def test_server_range_and_bounds(self):
        with tempfile.TemporaryDirectory() as d:
            data=b'a'*(2*1024*1024+17);(Path(d)/'fixture.tif').write_bytes(data)
            counters={'requests':0,'bytes':0}
            server=start_fixture_server({'http_root':d,'http_port':0},counters)
            try:
                url='http://127.0.0.1:'+str(server.server_port)+'/fixture.tif'
                with urllib.request.urlopen(urllib.request.Request(url,headers={'Range':'bytes=0-9999999'})) as response:
                    self.assertEqual(response.status,206);self.assertEqual(response.read(),data)
                self.assertEqual(counters,{'requests':1,'bytes':len(data)})
                with self.assertRaises(Exception):urllib.request.urlopen(urllib.request.Request(url,headers={'Range':'bytes=9999999-10000000'}))
            finally:server.shutdown();server.server_close()
    def test_extension_rejects_changes(self):
        manifest={'acceptance_policy':POLICY,'fixtures':{'a':'hash'},'jdk':'21','installed_artifacts':{'jar':'hash'}}
        previous={'manifest':copy.deepcopy(manifest),'cases':[]}
        validate_extension(previous,manifest)
        for field in manifest:
            changed=copy.deepcopy(manifest);changed[field]='different'
            with self.assertRaises(ValueError):validate_extension(previous,changed)
