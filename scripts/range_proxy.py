"""Budgeted public COG proxy. No content cache; bounded buffers and source identity checks."""
import threading
import urllib.request
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

class RangeProxy:
    def __init__(self, url, budget=1024**3):
        self.url=url; self.budget=budget; self.bytes=0; self.requests=0
        self.failure=None; self.identity=None; self.lock=threading.Lock()
        owner=self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_GET(self):
                match=re.fullmatch(r'bytes=(\d+)-(\d+)',self.headers.get('Range',''))
                if not match:self.send_error(400,'Explicit Range required');return
                start,end=map(int,match.groups())
                if start>end:self.send_error(416);return
                with owner.lock:
                    if owner.failure or end-start+1>owner.budget-owner.bytes:
                        owner.failure=owner.failure or 'Transfer budget exceeded';self.send_error(429,owner.failure);return
                    try:
                        request=urllib.request.Request(owner.url,headers={'Range':self.headers['Range'],'Accept-Encoding':'identity'})
                        with urllib.request.urlopen(request,timeout=30) as response:
                            content_range=response.headers.get('Content-Range','')
                            parsed=re.fullmatch(r'bytes (\d+)-(\d+)/(\d+)',content_range)
                            if response.status!=206 or not parsed or int(parsed[1])!=start or int(parsed[2])>end:
                                raise ValueError('Invalid upstream Range response')
                            identity={'size':int(parsed[3]),'last_modified':response.headers.get('Last-Modified'),'etag':response.headers.get('ETag')}
                            if owner.identity is not None and owner.identity!=identity:raise ValueError('Upstream source changed')
                            owner.identity=identity;owner.requests+=1
                            self.send_response(206)
                            for key in ('Content-Range','Content-Length','ETag','Last-Modified'):
                                if response.headers.get(key):self.send_header(key,response.headers[key])
                            self.end_headers()
                            remaining=int(parsed[2])-start+1
                            while remaining:
                                block=response.read(min(65536,remaining))
                                if not block:raise IOError('Truncated upstream response')
                                owner.bytes+=len(block);remaining-=len(block)
                                self.wfile.write(block)
                    except Exception as error:
                        owner.failure=str(error)
                        self.close_connection=True
        self.server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
    def __enter__(self):
        threading.Thread(target=self.server.serve_forever,daemon=True).start()
        return self
    @property
    def address(self):return 'http://127.0.0.1:'+str(self.server.server_port)+'/source.tif'
    def __exit__(self,*args):self.server.shutdown();self.server.server_close()
    def report(self):return {'url':self.url,'identity':self.identity,'requests':self.requests,'bytes':self.bytes,'budget':self.budget,'failure':self.failure,'snapshot_guaranteed':bool(self.identity and self.identity.get('etag') and not self.identity['etag'].startswith('W/'))}
