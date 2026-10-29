"""Run the PortMaster launcher (linux/portmaster/PokeDaisy.sh) from a real --portmaster package against a
stand-in PortMaster: its control.txt (the roms root, the controller mapping, gptokeyb, pm_finish) found
through $XDG_DATA_HOME, as on a firmware that keeps PortMaster there. No device, no firmware."""
from pathlib import Path
import os, signal, subprocess, sys, tempfile, time, zipfile
binary = Path(sys.argv[1]).resolve()
arch = {62: 'x86_64', 183: 'aarch64'}[int.from_bytes(binary.read_bytes()[18:20], 'little')]
package = Path(__file__).with_name('package.py')
with tempfile.TemporaryDirectory(prefix='pd-portmaster-') as d:
    d = Path(d)
    subprocess.run([sys.executable, str(package), '--portmaster', str(binary), str(d / 'out')], check=True,
                   capture_output=True)
    ports = d / 'roms' / 'ports'
    ports.mkdir(parents=True)
    with zipfile.ZipFile(d / 'out' / f'pokedaisy-portmaster-{arch}.zip') as z:
        z.extractall(d / 'out')
    for f in (d / 'out' / f'pokedaisy-portmaster-{arch}').iterdir():
        f.rename(ports / f.name)
    for f in (ports / 'PokeDaisy.sh', ports / 'pokedaisy' / 'pokedaisy-linux'):
        f.chmod(0o755)  # zipfile doesn't restore the mode; PortMaster's installer does
    pm = d / 'xdg' / 'PortMaster'
    pm.mkdir(parents=True)
    (pm / 'gptokeyb').write_text('#!/bin/sh\necho "gptokeyb $*" > "$GPTOKEYB_MARK"\n')
    (pm / 'gptokeyb').chmod(0o755)
    (pm / 'control.txt').write_text(
        f'directory="{str(d / "roms").lstrip("/")}"\n'
        'get_controls() { sdl_controllerconfig="00000000000000000000000000000000,PortMaster test pad,a:b0,b:b1"; }\n'
        f'GPTOKEYB="{pm / "gptokeyb"}"\nESUDO=""\nCFW_NAME=test\n'
        'pm_finish() { :; }\n')
    env = dict(os.environ, XDG_DATA_HOME=str(d / 'xdg'), GPTOKEYB_MARK=str(d / 'gptokeyb.ran'),
               SDL_VIDEODRIVER='dummy', SDL_AUDIODRIVER='dummy', SDL_RENDER_DRIVER='software')
    p = subprocess.Popen(['bash', str(ports / 'PokeDaisy.sh')], env=env, start_new_session=True,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    log = ports / 'pokedaisy' / 'log.txt'
    for _ in range(100):  # the game list is up once SDL has opened its window
        if log.exists() and 'Video backend' in log.read_text():
            break
        time.sleep(0.1)
    os.killpg(p.pid, signal.SIGTERM)
    p.wait(timeout=10)
    text = log.read_text()
    assert 'Video backend' in text, text
    assert all((ports / 'pokedaisy' / n).is_dir() for n in ('roms', 'saves', 'states'))
    assert (d / 'gptokeyb.ran').read_text().strip() == 'gptokeyb pokedaisy-linux'
print('PASS PortMaster launcher: control.txt found, the port folder made, gptokeyb started, the game list up')
