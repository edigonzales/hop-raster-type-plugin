import unittest,threading,urllib.request
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from range_proxy import RangeProxy
class RangeProxyTest(unittest.TestCase):
    def test_budget_and_identity(self):
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_GET(self):
                self.send_response(206);self.send_header('Content-Range','bytes 0-3/100');self.send_header('Content-Length','4');self.send_header('ETag',self.server.etag);self.end_headers();self.wfile.write(b'test')
        server=ThreadingHTTPServer(('127.0.0.1',0),Handler);server.etag='"one"'
        threading.Thread(target=server.serve_forever,daemon=True).start()
        url='http://127.0.0.1:'+str(server.server_port)
        try:
            with RangeProxy(url,budget=7) as proxy:
                with urllib.request.urlopen(urllib.request.Request(proxy.address,headers={'Range':'bytes=0-3'})) as r:self.assertEqual(r.read(),b'test')
                with self.assertRaises(Exception):urllib.request.urlopen(urllib.request.Request(proxy.address,headers={'Range':'bytes=0-3'}))
                self.assertEqual(proxy.bytes,4);self.assertEqual(proxy.failure,'Transfer budget exceeded')
            with RangeProxy(url) as proxy:
                with urllib.request.urlopen(urllib.request.Request(proxy.address,headers={'Range':'bytes=0-3'})) as r:r.read()
                server.etag='"two"'
                with self.assertRaises(Exception):urllib.request.urlopen(urllib.request.Request(proxy.address,headers={'Range':'bytes=0-3'}))
                self.assertIn('changed',proxy.failure)
        finally:server.shutdown();server.server_close()
if __name__=='__main__':unittest.main()
