#!/usr/bin/env python3
"""Frozen old/native actual CLI and disposable Git/filesystem acceptance."""
import hashlib
import os
from pathlib import Path
import re
import signal
import subprocess
import tempfile
import time

REPO = Path(__file__).resolve().parents[3]
NATIVE = REPO / 'orchestrator/android-orchestrator/pixel-health/target/release/pixel-workspace-cleanup'
REF = '62d1023'
OLD = {name: subprocess.check_output(['git','-C',str(REPO),'show',f'{REF}:tools/pixel/{name}']) for name in ('cleanup_workspace.sh','artifact_retention.sh')}


def fixture(root, tracked=False):
    subprocess.run(['git','-C',str(root),'init','-q'],check=True)
    for name in ('cleanup_workspace.sh','artifact_retention.sh'):
        path=root/'tools/pixel'/name
        path.parent.mkdir(parents=True,exist_ok=True)
        path.write_bytes(OLD[name])
    for rel, stale in (
        ('output/old/deep/drop.txt',True),('output/mixed/drop.txt',True),('output/mixed/keep.txt',False),
        ('.artifacts/recent-parent/drop.txt',True),('.codex-tmp/old/drop.txt',True),
        ('orchestrator/.artifacts/old/drop.tar',True),('workloads/train-bot/output/old/drop.txt',True),
        ('workloads/train-bot/.artifacts/old/drop.txt',True),('state/browser-use/keep',True),
        ('ops/evidence/keep',True),('outside/keep',True),('output/ā space.txt',True),
    ):
        path=root/rel;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(b'fixture payload\n')
        os.utime(path,(time.time()-300000 if stale else time.time()-5,)*2)
    (root/'output/old-link').symlink_to('../outside',target_is_directory=True)
    (root/'output/dangling').symlink_to('../absent')
    for path in sorted(root.rglob('*'),key=lambda p:len(p.parts),reverse=True):
        if path.is_symlink():os.utime(path,(time.time()-300000,)*2,follow_symlinks=False)
        elif path.is_dir() and '.git' not in path.parts and path!=root/'output':
            os.utime(path,(time.time()-300000,)*2)
    if tracked:
        subprocess.run(['git','-C',str(root),'add','-f','output/mixed/drop.txt'],check=True)


def normalize(data, root):
    return re.sub(rb'\[\d{4}-\d\d-\d\dT[^]]+\]',b'[TIME]',data.replace(str(root).encode(),b'ROOT'))


def snapshot(root):
    result={}
    for current,dirs,files in os.walk(root,followlinks=False):
        dirs[:]=[d for d in dirs if d!='.git']
        for name in dirs+files:
            path=Path(current)/name;rel=str(path.relative_to(root))
            if rel.startswith('tools/'):continue
            if path.is_symlink():value=('link',os.readlink(path).replace(str(root),'ROOT'))
            elif path.is_dir():value=('dir',)
            else:value=('file',hashlib.sha256(path.read_bytes()).hexdigest())
            result[rel]=value
    return result


def case(options=(),hours='72',tracked=False,retention=False):
    with tempfile.TemporaryDirectory(prefix='pixel-cleanup-parity-') as raw:
        base=Path(raw);outputs=[]
        for owner in ('old','native'):
            root=base/owner;root.mkdir();fixture(root,tracked)
            env=dict(os.environ,PIXEL_ARTIFACT_RETENTION_HOURS=hours,PYTHONDONTWRITEBYTECODE='1')
            if retention:
                args=['bash','-c','source "$1"; pixel_artifact_retention_prune "$2" "$3"', 'fixture',str(root/'tools/pixel/artifact_retention.sh'),hours,str(root/'output')] if owner=='old' else [str(NATIVE),'retention',hours,str(root/'output')]
            else:
                args=['bash',str(root/'tools/pixel/cleanup_workspace.sh'),*options] if owner=='old' else [str(NATIVE),'workspace',str(root),*options]
            result=subprocess.run(args,env=env,capture_output=True,timeout=30)
            outputs.append((result.returncode,normalize(result.stdout,root),normalize(result.stderr,root),snapshot(root)))
            assert (root/'state/browser-use/keep').read_bytes()==b'fixture payload\n'
            assert (root/'ops/evidence/keep').read_bytes()==b'fixture payload\n'
            assert (root/'outside/keep').read_bytes()==b'fixture payload\n'
        assert outputs[0]==outputs[1],(options,hours,tracked,retention,outputs)


count=0
for options in ((),('--check',),('--dry-run',),('--check','--dry-run'),('--dry-run','--check'),('--help',),('--bad',)):
    for tracked in (False,True):case(options,tracked=tracked);count+=1
for hours in ('0','24.5','1e5','-1',' 72 ','7_2'):
    case(('--dry-run',),hours=hours);count+=1
for hours in ('72','0','-1','invalid'):
    case(hours=hours,retention=True);count+=1
print(f'WORKSPACE_CLEANUP_CLI_PARITY_OK comparisons={count} protected_state_unchanged=true')

# Demonstrate the two unsafe old inputs before proving the native guards. All
# effects are confined to this helper-owned directory.
for defect in ('nan', 'linked-root', 'linked-workload'):
    with tempfile.TemporaryDirectory(prefix='pixel-cleanup-guard-') as raw:
        base=Path(raw)
        for owner in ('old','native'):
            root=base/owner;root.mkdir();fixture(root)
            outside=root/'outside';victim=outside/'stale';victim.write_bytes(b'keep')
            os.utime(victim,(time.time()-300000,)*2)
            target=root/'output'
            hours='nan' if defect=='nan' else '72'
            if defect=='linked-root':
                target=root/'linked-root';target.symlink_to('outside',target_is_directory=True)
            if defect=='linked-workload':
                victim=outside/'output/stale';victim.parent.mkdir();victim.write_bytes(b'keep')
                os.utime(victim,(time.time()-300000,)*2)
                (root/'workloads/linked').symlink_to('../outside',target_is_directory=True)
                args=['bash',str(root/'tools/pixel/cleanup_workspace.sh')] if owner=='old' else [str(NATIVE),'workspace',str(root)]
            else:args=['bash','-c','source "$1"; pixel_artifact_retention_prune "$2" "$3"','fixture',str(root/'tools/pixel/artifact_retention.sh'),hours,str(target)] if owner=='old' else [str(NATIVE),'retention',hours,str(target)]
            result=subprocess.run(args,capture_output=True,timeout=20)
            assert result.returncode==0,result.stderr
            if defect=='nan':
                assert (root/'output/mixed/keep.txt').exists()==(owner=='native')
            else:assert victim.exists()==(owner=='native')
print('WORKSPACE_CLEANUP_GUARDS_OK old_unsafe_effect_reproduced=true native_effects=0')

for owner in ('old','native'):
    with tempfile.TemporaryDirectory(prefix='pixel-cleanup-failure-') as raw:
        root=Path(raw);fixture(root)
        args=['bash',str(root/'tools/pixel/cleanup_workspace.sh')] if owner=='old' else [str(NATIVE),'workspace',str(root)]
        locked=root/'output/locked';locked.mkdir();victim=locked/'stale';victim.write_bytes(b'keep')
        os.utime(victim,(time.time()-300000,)*2);locked.chmod(0o500)
        try:
            result=subprocess.run(args,capture_output=True,timeout=20)
            assert result.returncode==1 and victim.exists() and b'stale generated output remains' in result.stdout,(owner,result.stdout,result.stderr)
        finally:locked.chmod(0o700)
        assert subprocess.run(args,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,timeout=20).returncode==0
        assert not victim.exists()
        # A closed report pipe fails before any cleanup effect.
        file=root/'output/keep-on-report-failure';file.write_bytes(b'keep');os.utime(file,(time.time()-300000,)*2)
        process=subprocess.Popen(args,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,start_new_session=True)
        process.stdout.close()
        assert process.wait(timeout=20)!=0 and file.exists(),owner
        assert subprocess.run(args,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,timeout=20).returncode==0
print('WORKSPACE_CLEANUP_FAILURE_RECOVERY_OK permissions_and_closed_report_pipe=true')

for sig in (signal.SIGTERM,signal.SIGINT,signal.SIGHUP):
    with tempfile.TemporaryDirectory(prefix='pixel-cleanup-interruption-') as raw:
        root=Path(raw);fixture(root)
        paths=[]
        for i in range(2500):
            path=root/'output'/f'interrupt-{i:04}';path.write_bytes(b'old')
            os.utime(path,(time.time()-300000,)*2);paths.append(path)
        # The public retention command owns the same deletion loop as packaging.
        process=subprocess.Popen([str(NATIVE),'retention','72',str(root/'output')],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,start_new_session=True)
        deadline=time.monotonic()+10
        while process.poll() is None and all(p.exists() for p in paths) and time.monotonic()<deadline:time.sleep(.001)
        assert process.poll() is None,'fixture must interrupt a real in-flight deletion'
        os.killpg(process.pid,sig);process.communicate(timeout=10)
        assert process.returncode<0 and (root/'output/mixed/keep.txt').exists()
        result=subprocess.run([str(NATIVE),'workspace',str(root)],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,timeout=20)
        assert result.returncode==0 and not any(p.exists() for p in paths),result.stderr
        assert (root/'state/browser-use/keep').exists() and (root/'ops/evidence/keep').exists()
print('WORKSPACE_CLEANUP_INTERRUPTION_OK signals=3 actual_partial_deletion_restart=true')

with tempfile.TemporaryDirectory(prefix='pixel-cleanup-no-git-') as raw:
    root=Path(raw);fixture(root)
    import shutil
    shutil.rmtree(root/'.git')
    before=snapshot(root)
    result=subprocess.run([str(NATIVE),'workspace',str(root)],capture_output=True,timeout=20)
    assert result.returncode==0 and b'git metadata unavailable' in result.stdout and snapshot(root)==before
    result=subprocess.run([str(NATIVE),'workspace',str(root)],env=dict(os.environ,PATH=str(root/'missing-bin')),capture_output=True,timeout=20)
    assert result.returncode==2 and result.stderr==b'cleanup_workspace.sh requires git\n' and snapshot(root)==before
print('WORKSPACE_CLEANUP_NO_GIT_OK zero_effects=true')
