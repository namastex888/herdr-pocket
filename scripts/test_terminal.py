#!/usr/bin/env python3
"""Browser regression checks for the bundled terminal (Python Playwright + Chromium)."""
import base64, functools, http.server, json, pathlib, shutil, threading
from playwright.sync_api import sync_playwright

ASSETS = pathlib.Path(__file__).resolve().parents[1] / 'app/src/main/assets/terminal'
class Handler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args): pass
server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), functools.partial(Handler, directory=str(ASSETS)))
threading.Thread(target=server.serve_forever, daemon=True).start()
try:
    with sync_playwright() as pw:
        chromium = shutil.which('chromium') or shutil.which('chromium-browser')
        browser = pw.chromium.launch(**({'executable_path': chromium} if chromium else {}), args=['--no-sandbox'])
        page = browser.new_page(viewport={'width': 420, 'height': 640})
        failures = []
        page.on('pageerror', lambda error: failures.append(str(error)))
        paint = base64.b64encode(b'\x1b[2J\x1b[H\x1b[38;2;150;178;215mHerdr fixture\x1b[0m\r\nReady for input.').decode()
        page.add_init_script('''window.__keys=[];window.Pocket={
          failed:m=>window.__error=m, painted:()=>{}, resized:()=>{},
          input:b=>window.__keys.push(b),
          ready:()=>{window.__ready=true;window.resetTerminal(1);window.output(1,1,''' + json.dumps(paint) + ''')}
        };''')
        page.goto(f'http://127.0.0.1:{server.server_port}/index.html')
        page.wait_for_function('window.__ready === true')
        page.wait_for_function("document.querySelector('.xterm-rows').innerText.includes('Herdr fixture')")
        assert page.evaluate('terminal.rows') > 2
        # Android WebView can leave percentage-height parents at zero during startup.
        page.add_style_tag(content='html,body{height:0!important}')
        page.set_viewport_size({'width': 420, 'height': 320})
        page.wait_for_timeout(300)
        assert page.evaluate("document.getElementById('terminal').getBoundingClientRect().height") == 320
        assert page.evaluate('terminal.rows') >= 10
        # An IME/layout transition must never shrink the renderer to its broken one-row state.
        page.set_viewport_size({'width': 420, 'height': 1})
        page.wait_for_timeout(300)
        assert page.evaluate('terminal.rows') >= 2
        page.set_viewport_size({'width': 420, 'height': 640})
        page.wait_for_timeout(300)
        page.evaluate('window.focusTerminal()')
        page.keyboard.type('hello-terminal')
        page.keyboard.press('Enter')
        keys = b''.join(base64.b64decode(s) for s in page.evaluate('window.__keys'))
        assert b'hello-terminal\r' in keys
        # A finger drag scrolls shell history without sending keys to the shell.
        page.evaluate("""async () => {
          window.__keys=[];
          await new Promise(done=>terminal.write('\\r\\n'+Array.from({length:160},(_,i)=>'history-'+i).join('\\r\\n'),done));
          terminal.scrollToBottom();
          window.swipe=(from,to)=>{
            const target=document.querySelector('.xterm-screen');
            const touch=y=>new Touch({identifier:1,target,clientX:120,clientY:y,pageX:120,pageY:y});
            target.dispatchEvent(new TouchEvent('touchstart',{bubbles:true,cancelable:true,touches:[touch(from)]}));
            target.dispatchEvent(new TouchEvent('touchmove',{bubbles:true,cancelable:true,touches:[touch(to)]}));
            target.dispatchEvent(new TouchEvent('touchend',{bubbles:true,cancelable:true,touches:[],changedTouches:[touch(to)]}));
          };
          swipe(200,440);
        }""")
        page.wait_for_timeout(100)
        assert page.evaluate('terminal.buffer.active.viewportY < terminal.buffer.active.baseY')
        assert page.evaluate('window.__keys.length') == 0
        assert page.locator('#latest').is_visible()
        old_position = page.evaluate('terminal.buffer.active.viewportY')
        page.evaluate("terminal.write('\\r\\nnew-output')")
        page.wait_for_timeout(100)
        assert page.evaluate('terminal.buffer.active.viewportY') == old_position
        page.locator('#latest').click()
        assert page.evaluate('terminal.buffer.active.viewportY === terminal.buffer.active.baseY')
        # Mouse-aware alternate-screen applications receive encoded wheel input.
        page.evaluate("""async () => {
          await new Promise(done=>terminal.write('\\x1b[?1049h\\x1b[?1000h\\x1b[?1006h',done));
          window.__keys=[];swipe(200,440);
        }""")
        mouse_keys = b''.join(base64.b64decode(s) for s in page.evaluate('window.__keys'))
        assert b'\x1b[<64;' in mouse_keys, mouse_keys
        # Apps without mouse mode get xterm's alternate-screen cursor scrolling.
        page.evaluate("""async () => {
          await new Promise(done=>terminal.write('\\x1b[?1000l',done));
          window.__keys=[];swipe(200,440);
        }""")
        app_keys = b''.join(base64.b64decode(s) for s in page.evaluate('window.__keys'))
        assert b'\x1b[A' in app_keys, app_keys
        assert not failures, failures
        assert not page.evaluate('window.__error || null')
        browser.close()
        print('PASS: paint, keyboard resize/input, touch history, hold position on output, latest, app mouse/alternate scrolling')
finally:
    server.shutdown()
