#!/usr/bin/env python3
"""Browser regression checks for the bundled terminal (Python Playwright + Chromium)."""
import base64, functools, http.server, json, pathlib, shutil, threading, time
from playwright.sync_api import sync_playwright

ASSETS = pathlib.Path(__file__).resolve().parents[1] / 'app/src/main/assets/terminal'
def wait_for(page, expression, timeout=10):
    """Poll with page.evaluate: wait_for_function evaluates through eval, which the page's CSP forbids."""
    deadline = time.monotonic() + timeout
    while not page.evaluate(expression):
        if time.monotonic() > deadline: raise AssertionError(f'timed out waiting for {expression}')
        page.wait_for_timeout(50)
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
          failed:m=>window.__error=m, painted:()=>{}, resized:()=>{}, fontSize:n=>window.__font=n, scrolled:u=>(window.__scrolled=window.__scrolled||[]).push(u),
          input:b=>window.__keys.push(b),
          ready:()=>{window.__ready=true;window.resetTerminal(1);window.output(1,1,''' + json.dumps(paint) + ''')}
        };''')
        page.goto(f'http://127.0.0.1:{server.server_port}/index.html')
        wait_for(page, 'window.__ready === true')
        wait_for(page, "document.querySelector('.xterm-rows').innerText.includes('Herdr fixture')")
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
        # Scrolling back reports up once per gesture; scrolling on reports down.
        assert page.evaluate('window.__scrolled') == [True], page.evaluate('window.__scrolled')
        page.evaluate('swipe(440,300)')
        assert page.evaluate('window.__scrolled') == [True, False], page.evaluate('window.__scrolled')
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
        # Two-finger pinch changes and reports the text size, without typing or scrolling.
        page.evaluate("terminal.write('\\x1b[?1049l')")
        before = page.evaluate('[terminal.options.fontSize, terminal.cols]')
        page.evaluate("""() => {
          window.__keys=[];
          const target=document.querySelector('.xterm-screen');
          const t=(id,x)=>new Touch({identifier:id,target,clientX:x,clientY:200,pageX:x,pageY:200});
          target.dispatchEvent(new TouchEvent('touchstart',{bubbles:true,cancelable:true,touches:[t(1,150),t(2,250)]}));
          target.dispatchEvent(new TouchEvent('touchmove',{bubbles:true,cancelable:true,touches:[t(1,100),t(2,300)]}));
          target.dispatchEvent(new TouchEvent('touchend',{bubbles:true,cancelable:true,touches:[t(1,100)],changedTouches:[t(2,300)]}));
          target.dispatchEvent(new TouchEvent('touchend',{bubbles:true,cancelable:true,touches:[],changedTouches:[t(1,100)]}));
        }""")
        page.wait_for_timeout(300)
        after = page.evaluate('[terminal.options.fontSize, terminal.cols]')
        assert after[0] == min(24, before[0] * 2) and after[1] < before[1], (before, after)
        assert page.evaluate('window.__font') == after[0]
        assert page.evaluate('window.__keys.length') == 0
        # A third finger ends the pinch, and the new size is still reported.
        page.evaluate("""() => {
          window.__font=null;
          const target=document.querySelector('.xterm-screen');
          const t=(id,x)=>new Touch({identifier:id,target,clientX:x,clientY:200,pageX:x,pageY:200});
          target.dispatchEvent(new TouchEvent('touchstart',{bubbles:true,cancelable:true,touches:[t(1,100),t(2,300)]}));
          target.dispatchEvent(new TouchEvent('touchmove',{bubbles:true,cancelable:true,touches:[t(1,150),t(2,250)]}));
          target.dispatchEvent(new TouchEvent('touchstart',{bubbles:true,cancelable:true,touches:[t(1,150),t(2,250),t(3,200)]}));
        }""")
        assert page.evaluate('window.__font') == page.evaluate('terminal.options.fontSize') < after[0]
        page.evaluate('window.setFontSize(99)')
        assert page.evaluate('terminal.options.fontSize') == 24
        assert not failures, failures
        assert not page.evaluate('window.__error || null')
        # The app passes the saved text size in the page URL.
        sized = browser.new_page(viewport={'width': 420, 'height': 640})
        sized.add_init_script('window.Pocket={failed:()=>{},painted:()=>{},resized:()=>{},input:()=>{},fontSize:()=>{},ready:()=>{window.__ready=true}};')
        sized.goto(f'http://127.0.0.1:{server.server_port}/index.html?font=17')
        wait_for(sized, 'window.__ready === true')
        assert sized.evaluate('terminal.options.fontSize') == 17
        # Links: a tap on a web link hands it to the app; OSC 8 links ask the app to confirm their target.
        links = browser.new_page(viewport={'width': 420, 'height': 640})
        links.add_init_script('''window.__links=[];window.__keys=[];window.Pocket={failed:m=>window.__error=m,painted:()=>{},resized:()=>{},
          input:b=>window.__keys.push(b),fontSize:()=>{},openUrl:(u,c)=>window.__links.push([u,c]),ready:()=>{window.__ready=true;window.resetTerminal(1)}};''')
        links.on('pageerror', lambda error: failures.append(str(error)))
        links.goto(f'http://127.0.0.1:{server.server_port}/index.html')
        wait_for(links, 'window.__ready === true')
        links.evaluate("""() => {
          const target=()=>document.querySelector('.xterm-screen');
          const point=(row,col)=>{const s=target().getBoundingClientRect();return [s.left+(col+.5)*s.width/terminal.cols,s.top+(row+.5)*s.height/terminal.rows]};
          const touch=(x,y)=>new Touch({identifier:1,target:target(),clientX:x,clientY:y,pageX:x,pageY:y});
          window.tapCell=(row,col)=>{window.__links=[];const [x,y]=point(row,col);
            target().dispatchEvent(new TouchEvent('touchstart',{bubbles:true,cancelable:true,touches:[touch(x,y)]}));
            return target().dispatchEvent(new TouchEvent('touchend',{bubbles:true,cancelable:true,touches:[],changedTouches:[touch(x,y)]}))};
          window.dragCell=(row,col)=>{window.__links=[];const [x,y]=point(row,col);
            target().dispatchEvent(new TouchEvent('touchstart',{bubbles:true,cancelable:true,touches:[touch(x,y)]}));
            target().dispatchEvent(new TouchEvent('touchmove',{bubbles:true,cancelable:true,touches:[touch(x,y+120)]}));
            target().dispatchEvent(new TouchEvent('touchend',{bubbles:true,cancelable:true,touches:[],changedTouches:[touch(x,y+120)]}))};
          window.cellCenter=point;
        }""")
        cols = links.evaluate('terminal.cols')
        long_url = 'https://example.com/' + 'a' * (cols + 20)
        app_url = 'https://wrap.example/' + 'b' * (cols + 10)
        links.evaluate('''text => new Promise(done => terminal.write(text, done))''',
            '\x1b[2J\x1b[HOpen https://a.dev/pull/2. Done\r\n'
            + long_url + '\r\n' + app_url[:cols] + '\r\n' + app_url[cols:] + '\r\n'
            + 'see \x1b]8;;https://example.com/target\x1b\\the docs\x1b]8;;\x1b\\ now\r\n'
            + 'and \x1b]8;;javascript:alert(1)\x1b\\this\x1b]8;;\x1b\\ too\r\n')
        assert links.evaluate('tapCell(0, 10)') is False, 'a tap on a link must not reach the terminal'
        assert links.evaluate('window.__links') == [['https://a.dev/pull/2', False]]
        assert links.evaluate('tapCell(0, 2)') is True and links.evaluate('window.__links') == []
        links.evaluate('tapCell(0, 25)')
        assert links.evaluate('window.__links') == [], 'punctuation after the link is not part of it'
        # A link the terminal wrapped, tapped on its second row.
        links.evaluate('tapCell(2, 3)')
        assert links.evaluate('window.__links') == [[long_url, False]]
        # A link a program wrapped itself by filling the row, tapped on either row.
        links.evaluate('tapCell(4, 3)')
        assert links.evaluate('window.__links') == [[app_url, False]]
        links.evaluate('tapCell(3, 3)')
        assert links.evaluate('window.__links') == [[app_url, False]]
        # Dragging over a link scrolls instead of opening it.
        links.evaluate('dragCell(0, 10)')
        assert links.evaluate('window.__links') == []
        # OSC 8: the shown text differs from the target, so the app is asked to confirm it.
        x, y = links.evaluate('cellCenter(5, 6)')
        links.evaluate('window.__links=[]')
        links.mouse.move(x, y); links.wait_for_timeout(100); links.mouse.click(x, y)
        links.wait_for_timeout(100)
        assert links.evaluate('window.__links') == [['https://example.com/target', True]], links.evaluate('window.__links')
        x, y = links.evaluate('cellCenter(6, 5)')
        links.evaluate('window.__links=[]')
        links.mouse.move(x, y); links.wait_for_timeout(100); links.mouse.click(x, y)
        links.wait_for_timeout(100)
        assert links.evaluate('window.__links') == [], 'non-web OSC 8 links are never handed over'
        assert links.evaluate('window.__keys.length') == 0
        assert not failures, failures
        browser.close()
        print('PASS: paint, keyboard resize/input, touch history, hold position on output, latest, app mouse/alternate scrolling, pinch text size, links')
finally:
    server.shutdown()
