#!/usr/bin/env python3
"""Local-only Android integration fixture. Requires paramiko and HERDR_TEST_HC.

Uses isolated core state and accepts only the public key copied from the test app.
Never run this against your normal core state directories.
"""
import json, os, pathlib, socket, subprocess, threading, tempfile
import paramiko

ROOT = pathlib.Path(os.environ.get('POCKET_TEST_ROOT', str(pathlib.Path(tempfile.gettempdir()) / 'herdr-pocket-test'))).resolve()
ROOT.mkdir(parents=True, exist_ok=True)
HC = os.environ['HERDR_TEST_HC']
ENV = dict(os.environ, XDG_STATE_HOME=str(ROOT/'state'), XDG_CONFIG_HOME=str(ROOT/'config'), XDG_RUNTIME_DIR=str(ROOT/'run'), HC_SESSION='default')
ENV.pop('HC_SOCKET', None)
(ROOT/'run').mkdir(exist_ok=True)
KEY = ROOT/'host_key'
if not KEY.exists(): paramiko.RSAKey.generate(2048).write_private_key_file(str(KEY))
HOSTKEY = paramiko.RSAKey.from_private_key_file(str(KEY))
PUBLIC = ROOT/'phone.pub'


def rpc(method, params=None):
    p = subprocess.Popen([HC, 'connect'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=ENV)
    try:
        p.stdin.write((json.dumps(dict(jsonrpc='2.0', id=1, method=method, params=params or {}))+'\n').encode()); p.stdin.flush()
        result = json.loads(p.stdout.readline())
        if 'error' in result: raise RuntimeError(result['error']['message'])
        return result['result']
    finally:
        p.stdin.close(); p.wait(timeout=10)


if not rpc('terminal.list')['terminals']:
    fixture = ROOT/'terminal_fixture.py'
    fixture.write_text('''import sys,json
print('\\x1b[38;2;150;178;215mHerdr Pocket\\x1b[0m  ·  Live connection test',flush=True)
print('\\nConnected to an isolated herdr-core over SSH.\\n',flush=True)
print('Type a message below. The test program echoes it back.\\n',flush=True)
print('\\x1b]7501;state=idle:app=test-shell\\x07',end='',flush=True)
for line in sys.stdin:
 print('Received: '+line.strip(),flush=True)
''')
    terminal = rpc('terminal.run', dict(argv=['python3','-u',str(fixture)], cwd=str(ROOT)))
    rpc('doc.set', dict(name='herdr-app', version=0, value=dict(spaces=[dict(id='sp-device', name='Android verification', folder=str(ROOT), tabs=[dict(id='tb-live', name='Live terminal', root=dict(id='pane-live', type='block', content=dict(terminal=terminal['address'])))])])) )


class Server(paramiko.ServerInterface):
    def get_allowed_auths(self, username): return 'publickey'
    def check_auth_publickey(self, username, key):
        expected = PUBLIC.read_text().split() if PUBLIC.exists() else []
        return paramiko.AUTH_SUCCESSFUL if username == 'pocket' and len(expected) >= 2 and key.get_base64() == expected[1] else paramiko.AUTH_FAILED
    def check_channel_request(self, kind, chanid):
        return paramiko.OPEN_SUCCEEDED if kind == 'session' else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED
    def check_channel_exec_request(self, channel, command):
        # The fixture permits only the same hc connect operation as the phone.
        # No remote shell command from the client is executed by this test server.
        if b'connect' not in command: return False
        threading.Thread(target=bridge, args=(channel,), daemon=True).start()
        return True


def bridge(channel):
    p = subprocess.Popen([HC, 'connect'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=ENV)
    def incoming():
        try:
            while True:
                data = channel.recv(65536)
                if not data: break
                p.stdin.write(data); p.stdin.flush()
        except (OSError, EOFError): pass
        finally:
            try: p.stdin.close()
            except OSError: pass
    threading.Thread(target=incoming, daemon=True).start()
    try:
        while True:
            data = os.read(p.stdout.fileno(),65536)
            if not data: break
            channel.sendall(data)
    except (OSError, EOFError): pass
    finally: channel.close(); p.terminate()


def serve(sock):
    t = paramiko.Transport(sock)
    try:
        t.add_server_key(HOSTKEY); t.start_server(server=Server())
        while t.is_active():
            channel = t.accept(2)
    except (paramiko.SSHException, EOFError, OSError): pass
    finally: t.close()


listener = socket.socket()
listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
listener.bind(('127.0.0.1', 2222)); listener.listen(8)
print('Local SSH fixture ready on loopback:2222; core state is isolated.', flush=True)
try:
    while True:
        sock,_ = listener.accept(); threading.Thread(target=serve,args=(sock,),daemon=True).start()
finally:
    listener.close()
    subprocess.run([HC,'server','stop'],env=ENV,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
