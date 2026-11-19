# A fake SonarQube server for the M3 end-to-end spec of migrate-verify-to-executor-contract (task
# 7.1, PendingStartKillJarE2eSpec): the three Web API calls a quality-gate client makes, in the shape
# SonarQube answers them. It runs in a container on Docker's default bridge (SonarQubeFakeServer),
# where a box's egress guard reaches it by address, and keeps its state in memory, so it outlives
# every box and every factory instance of a spec, as a real server does.
#
#   POST /api/ce/submit?projectKey=<k>&revision=<sha>   a new analysis task: {"taskId": "AX<n>"}
#   GET  /api/ce/task?id=AX<n>                          IN_PROGRESS on its first read, then SUCCESS
#                                                       with "analysisId": "AN<n>"
#   GET  /api/qualitygates/project_status?analysisId=AN<n>   status OK, one passing condition
#
# A request without `Authorization: Bearer <SONAR_TOKEN_EXPECTED>` is refused with 401, as SonarQube
# refuses an unauthenticated client. Every request is recorded, with whether it carried the token
# (never the token itself), and `GET /__requests` returns the record for the spec, which reads it on
# the port Docker publishes, outside the guard. Run as `python3 server.py`: no interpreter line.
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

EXPECTED = 'Bearer ' + os.environ['SONAR_TOKEN_EXPECTED']
REQUESTS = []
TASKS = {}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.handle_call('GET')

    def do_POST(self):
        self.handle_call('POST')

    def handle_call(self, method):
        url = urlsplit(self.path)
        query = {name: values[0] for name, values in parse_qs(url.query).items()}
        if url.path == '/__requests':
            self.answer(200, REQUESTS)
            return
        authorized = self.headers.get('Authorization') == EXPECTED
        REQUESTS.append({'method': method, 'path': url.path, 'query': query, 'authorized': authorized})
        if not authorized:
            self.answer(401, {'errors': [{'msg': 'Authentication required'}]})
        elif method == 'POST' and url.path == '/api/ce/submit':
            task = 'AX%d' % (len(TASKS) + 1)
            TASKS[task] = 0
            self.answer(200, {'taskId': task})
        elif url.path == '/api/ce/task' and query.get('id') in TASKS:
            task = query['id']
            TASKS[task] += 1
            if TASKS[task] == 1:
                self.answer(200, {'task': {'id': task, 'status': 'IN_PROGRESS'}})
            else:
                self.answer(200, {'task': {'id': task, 'status': 'SUCCESS', 'analysisId': 'AN' + task[2:]}})
        elif url.path == '/api/qualitygates/project_status' and query.get('analysisId', '').startswith('AN'):
            condition = {'status': 'OK', 'metricKey': 'new_coverage', 'actualValue': '87.5', 'errorThreshold': '80'}
            self.answer(200, {'projectStatus': {'status': 'OK', 'conditions': [condition]}})
        else:
            self.answer(404, {'errors': [{'msg': 'No such resource'}]})

    def answer(self, code, body):
        data = json.dumps(body).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, format, *args):
        pass


ThreadingHTTPServer(('0.0.0.0', 9000), Handler).serve_forever()
