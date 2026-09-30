#!/usr/bin/env python3
"""Real frozen-shell/native CLI effects and durable state; no phone is contacted."""
import hashlib
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import threading

REPO = Path(__file__).resolve().parents[4]
CRATE = Path(__file__).resolve().parents[1]
SOURCE = REPO / 'orchestrator/android-orchestrator/app/src/main/assets/runtime/entrypoints'
NATIVE = Path(os.environ.get('PIXEL_LIFECYCLE_NATIVE', CRATE / 'target/release/pixel-runtime-cleanup'))
ORACLE = 'b4a66c24979136be2b95f8e923568e64a80bb945'
PREFIX = 'orchestrator/android-orchestrator/app/src/main/assets/runtime/entrypoints/'
HASHES = {
    'start': 'f07ea4a33b6cd2d8edda4ed32fecb28f0b9d99dd8d64b39d67d5289459c11a29',
    'stop': '3a24568c546f48f405eeaf63b7e3a62c5ee6b7489bd109a169adfeac69f44591',
    'health': '69f9f3a73d0790897056d05402ab58c0e2379b23c023ffbc06bd49a6bcce4f19',
    'lifecycle-lock': '804a8b5e3bce6422b2c8966ca8b60fabaa0abb2aac12873687114c56484d843d',
}

def write(file, content, mode=0o644):
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_bytes(content.encode() if isinstance(content, str) else content)
    file.chmod(mode)

def fixture(root, native, scenario):
    stack = root / 'stack'
    bin_dir = stack / 'bin'
    adapters = root / 'adapters'
    control = root / 'control'
    control.mkdir()
    write(control / 'effects', '')
    write(control / 'ready', '1' if scenario.startswith('healthy') else '0')
    write(control / 'debug', '1')
    write(control / 'secure', '1')
    for name in ['start', 'stop', 'health', 'lifecycle-lock']:
        filename = f'pixel-ticket-{name}.sh'
        if native:
            body = (SOURCE / filename).read_bytes()
        else:
            body = subprocess.check_output(['git', '-C', str(REPO), 'show', ORACLE + ':' + PREFIX + filename])
            assert hashlib.sha256(body).hexdigest() == HASHES[name]
        write(bin_dir / filename, body, 0o755)
    if native:
        shutil.copy2(NATIVE, bin_dir / 'pixel-runtime-cleanup')
    conf = stack / 'conf/apps/ticket-screen.env'
    runtime = stack / 'apps/ticket-screen/env/ticket-screen.env'
    config = 'TICKET_SCREEN_PORT=9388\nTICKET_SCREEN_START_TIMEOUT_SECONDS=1\nTICKET_SCREEN_STOP_SERVICE_WAIT_ATTEMPTS=2\n'
    if scenario == 'healthy-open': config += 'TICKET_SCREEN_OPEN_ORCHESTRATOR_ON_START=yes\n'
    write(conf, config)
    if scenario != 'start-missing-config': write(runtime, config if scenario != 'healthy-changed' else config + '# previous\n', 0o600)
    state = stack / 'apps/ticket-screen/state/ro-debuggable-before-ticket'
    saved = '0\nnull\n'
    if scenario in {'stop-legacy', 'stop-migrate-failure'}: saved = '0\n'
    if scenario == 'stop-saved-one': saved = '1\n1\n'
    if scenario == 'stop-invalid-debug': saved = 'bad\n0\n'
    if scenario == 'stop-invalid-secure': saved = '0\nbad\n'
    if scenario == 'stop-empty': saved = ''
    if not scenario.startswith('stop-missing'): write(state, saved, 0o600)
    if scenario == 'stop-missing-inactive': write(control / 'secure', '0')
    if scenario == 'stop-missing-null': write(control / 'secure', 'null')
    if scenario == 'stop-missing-invalid': write(control / 'debug', 'bad')
    write(adapters / 'sleep', '#!/bin/sh\nexit 0\n', 0o755)
    write(adapters / 'chcon', '#!/bin/sh\nexit 0\n', 0o755)
    write(adapters / 'am', '''#!/bin/sh
printf 'am %s\n' "$*" >> "$CONTROL/effects"
case "$*" in
  *ticket_start_server*) [ "$SCENARIO" != start-am-failure ] || exit 9; [ "$SCENARIO" = start-timeout ] || printf 1 > "$CONTROL/ready";;
  *force-stop*) printf 0 > "$CONTROL/listener";;
esac
[ "$SCENARIO" != signal ] || { printf ready > "$CONTROL/blocked"; exec /bin/sleep 30; }
''', 0o755)
    write(adapters / 'getprop', '#!/bin/sh\nprintf "getprop %s\\n" "$*" >> "$CONTROL/effects"\ncat "$CONTROL/debug"\n', 0o755)
    write(adapters / 'resetprop', '''#!/bin/sh
printf 'resetprop %s\n' "$*" >> "$CONTROL/effects"
[ "$SCENARIO" != stop-debug-failure ] || exit 9
printf %s "$2" > "$CONTROL/debug"
''', 0o755)
    write(adapters / 'settings', '''#!/bin/sh
printf 'settings %s\n' "$*" >> "$CONTROL/effects"
case "$1" in
 get) cat "$CONTROL/secure";;
 put|delete)
   [ "$SCENARIO" != stop-secure-failure ] || exit 9
   if [ "$1" = delete ]; then printf null; else printf %s "$4"; fi > "$CONTROL/secure";;
esac
''', 0o755)
    write(control / 'listener', '1' if scenario in {'stop-listener', 'stop-deep'} else '0')
    write(adapters / 'ss', '#!/bin/sh\n[ "$(cat "$CONTROL/listener")" != 1 ] || printf "LISTEN 0 8 127.0.0.1:9388 0.0.0.0:*\\n"\n', 0o755)
    write(adapters / 'dumpsys', '''#!/bin/sh
[ "$SCENARIO" != stop-service-failure ] || exit 9
[ "$SCENARIO" != stop-service ] || printf 'TicketStreamService\n'
''', 0o755)
    write(adapters / 'mv', '#!/bin/sh\n[ "$SCENARIO" != stop-migrate-failure ] || exit 9\nexec /bin/mv "$@"\n', 0o755)
    write(adapters / 'rm', '#!/bin/sh\ncase "$SCENARIO:$*" in stop-delete-failure:*ro-debuggable-before-ticket) exit 9;; esac\nexec /bin/rm "$@"\n', 0o755)
    write(control / 'health-adapter', '''#!/bin/sh
printf 'health %s\n' "$*" >> "$CONTROL/effects"
[ "$(cat "$CONTROL/ready")" = 1 ]
''', 0o755)
    write(adapters / 'curl', '''#!/bin/sh
printf 'curl %s\n' "$*" >> "$CONTROL/effects"
case "$SCENARIO" in health-http-fail) printf 503;; health-curl-lost) printf 200; exit 28;; *) printf 200;; esac
''', 0o755)
    env = dict(os.environ, PIXEL_STACK_ROOT=str(stack), CONTROL=str(control), SCENARIO=scenario,
               PATH=str(adapters) + ':' + os.environ['PATH'], PIXEL_TICKET_HEALTH_BIN=str(control / 'health-adapter'))
    if scenario.startswith('health-port-'):
        write(conf, 'TICKET_SCREEN_PORT=' + scenario.removeprefix('health-port-') + '\n')
        write(runtime, '')
    return stack, control, env

def receipt(stack, control, result):
    durable = {}
    for file in sorted((stack / 'apps').rglob('*')):
        if file.is_file():
            durable[str(file.relative_to(stack))] = (file.read_bytes(), file.stat().st_mode & 0o777)
    return (result.returncode, result.stdout, result.stderr, (control / 'effects').read_bytes(),
            (control / 'debug').read_bytes(), (control / 'secure').read_bytes(), durable)

def run(root, native, scenario, action, flags=()):
    stack, control, env = fixture(root, native, scenario)
    result = subprocess.run(['sh', str(stack / f'bin/pixel-ticket-{action}.sh'), *flags], env=env, capture_output=True, timeout=20)
    return receipt(stack, control, result)

def timeout_adapter(control):
    adapter = control / 'bounded-owner-read'
    write(adapter, '#!' + sys.executable + '''
import os, signal, subprocess, sys
p = subprocess.Popen(sys.argv[2:], start_new_session=True)
try: sys.exit(p.wait(timeout=float(sys.argv[1])))
except subprocess.TimeoutExpired:
    os.killpg(p.pid, signal.SIGKILL); p.wait(); sys.exit(124)
''', 0o755)
    return adapter

def lock_case(root, native, case):
    stack, control, env = fixture(root, native, 'healthy')
    env.update(TICKET_SCREEN_START_LOCK_WAIT_SECONDS='1', TICKET_LOCK_PROC_ROOT=str(control / 'proc'),
               TICKET_LOCK_TIMEOUT_BIN=str(timeout_adapter(control)))
    lock = stack / 'apps/ticket-screen/run/ticket-screen-start-stop.lock'
    lock.mkdir(parents=True)
    owner = subprocess.Popen(['/bin/sleep', '30'])
    writer = None
    try:
        pid = owner.pid if case not in {'dead', 'malformed', 'cr'} else 99999999
        raw = 'bad' if case == 'malformed' else str(pid) + ('\r' if case == 'cr' else '')
        write(lock / 'owner.pid', raw + '\n')
        cmdline = control / f'proc/{pid}/cmdline'
        if case not in {'missing', 'dead', 'malformed', 'cr'}:
            content = b'pixel-ticket-start.sh\0' if case == 'live-old' else (
                b'/data/local/pixel-stack/bin/pixel-runtime-cleanup\0pixel-ticket-start.sh\0' if case == 'live-native' else b'unrelated\0')
            write(cmdline, content)
        if case == 'blocked':
            cmdline.unlink(); os.mkfifo(cmdline)
            writer = subprocess.Popen(['sh', '-c', 'exec 3> "$1"; exec /bin/sleep 30', 'writer', str(cmdline)])
        if case == 'timeout':
            write(Path(env['TICKET_LOCK_TIMEOUT_BIN']), '#!/bin/sh\nexit 124\n', 0o755)
        if case == 'contested': write(lock / 'unrelated', 'must preserve')
        started = time.monotonic()
        result = subprocess.run(['sh', str(stack / 'bin/pixel-ticket-start.sh'), '--force'], env=env, capture_output=True, timeout=15)
        assert time.monotonic() - started < 9, case
        rejected = case in {'live-old', 'live-native', 'missing', 'blocked', 'timeout', 'contested'}
        assert result.returncode == int(rejected), (case, result)
        if rejected:
            if case == 'contested':
                assert not (lock / 'owner.pid').exists() and (lock / 'unrelated').read_text() == 'must preserve'
            else: assert (lock / 'owner.pid').read_text() == raw + '\n'
            assert not any(line.startswith('am ') for line in (control / 'effects').read_text().splitlines())
        else: assert not lock.exists()
        return repr(receipt(stack, control, result)).replace(str(root), '<OWNED>').replace(str(pid), '<PID>')
    finally:
        owner.terminate(); owner.wait()
        if writer: writer.terminate(); writer.wait()

def signal_case(root, native, sig):
    stack, control, env = fixture(root, native, 'signal')
    command = ['sh', str(stack / 'bin/pixel-ticket-start.sh'), '--force']
    proc = subprocess.Popen(command, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
    try:
        deadline = time.monotonic() + 5
        while not (control / 'blocked').exists():
            assert proc.poll() is None and time.monotonic() < deadline
            time.sleep(0.01)
        lock = stack / 'apps/ticket-screen/run/ticket-screen-start-stop.lock'
        assert (lock / 'owner.pid').read_text().strip() == str(proc.pid)
        os.killpg(proc.pid, sig)
        proc.communicate(timeout=5)
        if sig != signal.SIGKILL:
            assert proc.returncode == 143, (sig, proc.returncode)
            assert not lock.exists(), (sig, lock)
        else:
            assert lock.exists()
            env['SCENARIO'] = 'healthy'
            result = subprocess.run(command, env=env, capture_output=True, timeout=5)
            assert result.returncode == 0 and not lock.exists(), result
        return (control / 'effects').read_bytes()
    finally:
        if proc.poll() is None: os.killpg(proc.pid, signal.SIGKILL); proc.wait()

def wire_case(root, native, status, transport):
    stack, control, env = fixture(root, native, 'health')
    listener = socket.socket()
    listener.bind(('127.0.0.1', 0)); listener.listen()
    listener.settimeout(5)
    port = listener.getsockname()[1]
    write(stack / 'conf/apps/ticket-screen.env', f'TICKET_SCREEN_PORT={port}\n')
    write(stack / 'apps/ticket-screen/env/ticket-screen.env', '')
    isolated = root / 'wire-bin'; isolated.mkdir()
    for tool in ['sh', 'env', 'sed', 'tr', transport]:
        found = shutil.which(tool)
        assert found, f'actual {transport} adapter unavailable'
        (isolated / tool).symlink_to(found)
    env['PATH'] = str(isolated)
    request = []
    def serve():
        client, _ = listener.accept()
        with client:
            request.append(client.recv(4096))
            client.sendall(f'HTTP/1.1 {status} fixture\r\nContent-Length: 0\r\nConnection: close\r\n\r\n'.encode())
    worker = threading.Thread(target=serve)
    worker.start()
    try:
        result = subprocess.run(['sh', str(stack / 'bin/pixel-ticket-health.sh')], env=env, capture_output=True, timeout=5)
        worker.join(6)
        assert not worker.is_alive() and result.returncode == int(status != 200), (transport, status, result)
        assert request and request[0].startswith(b'GET /api/v1/health HTTP/1.1\r\n')
        return result.returncode, result.stdout, result.stderr
    finally: listener.close(); worker.join(6)

CASES = [(s, 'start', flags) for s, flags in [
    ('healthy', ()), ('healthy-open', ()), ('healthy-changed', ()),
    ('healthy', ('--force',)), ('healthy', ('--deep',)), ('healthy', ('--full',)),
    ('start-missing-config', ()), ('start-timeout', ()), ('start-am-failure', ()),
    ('healthy', ('bad',)),
]] + [(s, 'stop', ('--deep',) if s == 'stop-deep' else ()) for s in [
    'stop', 'stop-legacy', 'stop-saved-one', 'stop-invalid-debug', 'stop-invalid-secure', 'stop-empty',
    'stop-missing', 'stop-missing-inactive', 'stop-missing-null', 'stop-missing-invalid',
    'stop-secure-failure', 'stop-debug-failure', 'stop-delete-failure', 'stop-migrate-failure',
    'stop-listener', 'stop-deep', 'stop-service', 'stop-service-failure',
]] + [(s, 'health', ()) for s in [
    'health', 'health-http-fail', 'health-curl-lost', 'health-port-0', 'health-port-65536', 'health-port-bad',
]] + [('health', 'health', ('--deep',)), ('health', 'health', ('bad',)), ('stop', 'stop', ('--force',))]

def main():
    assert NATIVE.is_file(), f'build existing pixel-runtime-cleanup binary first: {NATIVE}'
    with tempfile.TemporaryDirectory(prefix='pixel-lifecycle-proof-') as temporary:
        base = Path(temporary)
        for index, (scenario, action, flags) in enumerate([] if '--locks-only' in sys.argv else CASES):
            a, b = base / f'{index}-old', base / f'{index}-native'
            a.mkdir(); b.mkdir()
            old = run(a, False, scenario, action, flags)
            new = run(b, True, scenario, action, flags)
            # Paths differ only because each real CLI receives an independently owned tree.
            normalize = lambda value, root: repr(value).replace(str(root), '<OWNED>')
            assert normalize(old, a) == normalize(new, b), (scenario, flags, old, new)
            print(f'PASS {action} {scenario} {list(flags)}', flush=True)
        if '--policy-only' not in sys.argv:
            for case in ['dead', 'malformed', 'cr', 'live-old', 'live-native', 'missing', 'blocked', 'timeout', 'unrelated', 'contested']:
                a, b = base / ('lock-old-' + case), base / ('lock-native-' + case)
                a.mkdir(); b.mkdir()
                assert lock_case(a, False, case) == lock_case(b, True, case), case
                print(f'PASS actual owned lock {case}', flush=True)
            for sig in [signal.SIGHUP, signal.SIGINT, signal.SIGTERM, signal.SIGKILL]:
                a, b = base / f'signal-old-{sig}', base / f'signal-native-{sig}'
                a.mkdir(); b.mkdir()
                assert signal_case(a, False, sig) == signal_case(b, True, sig), sig
                print(f'PASS process-group signal/recovery {sig}', flush=True)
        if '--locks-only' not in sys.argv:
            for transport in ['curl', 'nc']:
                for status in [200, 503]:
                    a, b = base / f'wire-old-{transport}-{status}', base / f'wire-native-{transport}-{status}'
                    a.mkdir(); b.mkdir()
                    assert wire_case(a, False, status, transport) == wire_case(b, True, status, transport)
                    print(f'PASS actual loopback {transport} HTTP{status}', flush=True)
    print(f'PASS selected frozen/native CLI/effect/state/lock/signal/wire journeys; oracle {ORACLE}', flush=True)

if __name__ == '__main__': main()
