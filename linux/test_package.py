"""Check the real native/cross package and keep its architecture-specific name."""
from pathlib import Path
import subprocess,sys,tempfile,zipfile
binary=Path(sys.argv[1]).resolve()
arch=int.from_bytes(binary.read_bytes()[18:20],'little')
name='pokedaisy-linux-'+{62:'x86_64',183:'aarch64'}[arch]
script=Path(__file__).with_name('package.py')
with tempfile.TemporaryDirectory(prefix='pd-linux-package-') as d:
    result=subprocess.run([sys.executable,str(script),str(binary),d],capture_output=True,text=True)
    assert result.returncode==0,result.stderr
    archive=Path(d)/(name+'.zip')
    assert archive.exists() and Path(result.stdout.strip())==archive
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None
        names=z.namelist();assert len(names)==12,names
        assert all(n.startswith(name+'/') for n in names)
        assert b'"label": "PokeDaisy"' in z.read(name+'/config.json') and z.read(name+'/icon.png')[:4]==b'\x89PNG'
        assert z.read(name+'/pokedaisy-linux')==binary.read_bytes()
        assert z.getinfo(name+'/launch.sh').external_attr>>16 & 0o111
        assert z.read(name+'/SOURCE.txt').startswith(b'Source: https://github.com/lidor30/pokedaisy')
        assert not any(n.lower().endswith(('.gb','.gbc','.gba','.sav','.ss','.srm','.bin')) for n in names)
    before=archive.read_bytes()
    result=subprocess.run([sys.executable,str(script),str(binary),d],capture_output=True,text=True)
    assert result.returncode!=0 and archive.read_bytes()==before
# The PortMaster layout: PokeDaisy.sh beside pokedaisy/, which holds the rest.
pm='pokedaisy-portmaster-'+{62:'x86_64',183:'aarch64'}[arch]
with tempfile.TemporaryDirectory(prefix='pd-linux-package-') as d:
    result=subprocess.run([sys.executable,str(script),'--portmaster',str(binary),d],capture_output=True,text=True)
    assert result.returncode==0,result.stderr
    with zipfile.ZipFile(Path(d)/(pm+'.zip')) as z:
        names=z.namelist();assert len(names)==10,names
        assert z.getinfo(pm+'/PokeDaisy.sh').external_attr>>16 & 0o111
        assert z.read(pm+'/pokedaisy/pokedaisy-linux')==binary.read_bytes()
        assert z.getinfo(pm+'/pokedaisy/pokedaisy-linux').external_attr>>16 & 0o111
        assert not any(n.endswith('launch.sh') for n in names)
        assert all(n==pm+'/PokeDaisy.sh' or n.startswith(pm+'/pokedaisy/') for n in names)
print('PASS architecture-specific package, actual executable, license/source records, overwrite refusal and the PortMaster layout')
