"""실제 Gradle 해석의 module receipt와 원본 metadata 보존 경계를 검사한다."""
import copy
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest

HELPER = Path(__file__).resolve().parents[1] / 'call_position_module_runtime.py'
spec = importlib.util.spec_from_file_location('module_runtime_tested', HELPER)
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

class ModuleRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.root = Path(self.temp.name)
        keys = sorted(m.EXPECTED_TOOLS)
        roots = ['org.jetbrains.kotlin:kotlin-build-tools-compat:2.4.20','org.jetbrains.kotlin:kotlin-build-tools-impl:2.4.20']
        graph = {k:[] for k in keys};graph[roots[1]] = [k for k in keys if k not in roots]
        artifacts=[];metadata=[]
        def file_row(key, extension, reason=None):
            group,name,version=key.split(':');relative=m.relative_path(group,name,version,extension)
            path=self.root/relative;path.parent.mkdir(parents=True,exist_ok=True);data=('public fixture '+key+' '+extension).encode();path.write_bytes(data)
            row={'group':group,'name':name,'version':version,'extension':extension,'path':str(path),'bytes':len(data),'sha256':m.digest(data)}
            if reason:row.update(relativePath=relative,reason=reason)
            else:row.update(classifier='')
            return row
        for key in keys:
            artifacts.append(file_row(key,'jar'));metadata.append(file_row(key,'pom','selected-module'))
        for key in ['org.jetbrains.kotlin:kotlin-stdlib:2.4.20','org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0']:
            metadata.append(file_row(key,'module','published-gradle-metadata'))
        metadata.append(file_row('org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.8.0','pom','imported-bom'))
        self.report={'roles':{'buildTools':{'roots':roots,'graph':graph,'artifacts':artifacts}},'moduleMirror':{
            'schemaVersion':1,'kotlinVersion':'2.4.20','requestedRoots':roots,'selectedModules':keys,'graph':copy.deepcopy(graph),
            'artifactlessModules':[],'naturalArtifactOrder':keys,'naturalFileOrder':list(reversed(keys)),
            'metadataReferences':[{'from':'org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0','to':'org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.8.0','reason':'imported-bom'}],
            'metadata':metadata}}
    def tearDown(self):self.temp.cleanup()
    def validate(self):return m.validate_module_capture(self.report,'2.4.20')
    def test_module_capture_and_two_orders(self):
        self.assertEqual(17,len(self.validate()['metadata']))
        self.assertNotEqual(self.validate()['naturalArtifactOrder'],self.validate()['naturalFileOrder'])
    def test_missing_extra_duplicate_mixed_metadata_rejected(self):
        original=copy.deepcopy(self.report)
        for mutate in [lambda x:x['metadata'].pop(),lambda x:x['metadata'].append(x['metadata'][0]),
                       lambda x:x['metadata'][0].update(version='2.4.10'),lambda x:x['metadata'][0].update(reason='parent'),
                       lambda x:x['metadata'][0].update(relativePath='../escape.pom'),lambda x:x.update(schemaVersion=True)]:
            self.report=copy.deepcopy(original);mutate(self.report['moduleMirror'])
            with self.assertRaises(ValueError):self.validate()
    def test_module_universe_and_graph_contradictions_rejected(self):
        original=copy.deepcopy(self.report)
        for mutate in [lambda x:x.update(artifactlessModules=['g:extra:1']),lambda x:x['selectedModules'].pop(),
                       lambda x:x['graph'].update({'g:extra:1':[]}),lambda x:x.update(metadataReferences=[]),
                       lambda x:x['naturalFileOrder'].append(x['naturalFileOrder'][0])]:
            self.report=copy.deepcopy(original);mutate(self.report['moduleMirror'])
            with self.assertRaises(ValueError):self.validate()
    def test_metadata_byte_mutation_rejected(self):
        Path(self.report['moduleMirror']['metadata'][0]['path']).write_bytes(b'changed')
        with self.assertRaises(ValueError):self.validate()
    def test_mirror_copies_original_bytes_without_alias_and_records_natural_order(self):
        mirror=self.root/'owned';receipt=self.root/'receipt.json'
        rows,paths=m.prepare_mirror(self.report,'2.4.20',mirror,receipt)
        self.assertEqual(31,len(rows));self.assertEqual(14,len(paths))
        self.assertEqual(m.strict_json(receipt.read_bytes())['naturalFileOrder'],self.report['moduleMirror']['naturalFileOrder'])
        for row in rows:
            self.assertNotEqual((mirror/row['path']).stat().st_ino,Path(row['source']).stat().st_ino)
        m.verify_mirror(mirror,rows)
        (mirror/'unexpected').write_bytes(b'x')
        with self.assertRaises(ValueError):m.verify_mirror(mirror,rows)
    def test_mirror_removed_or_mutated_entry_rejected(self):
        rows,_=m.prepare_mirror(self.report,'2.4.20',self.root/'owned',self.root/'receipt.json')
        target=self.root/'owned'/rows[0]['path'];target.write_bytes(b'changed')
        with self.assertRaises(ValueError):m.verify_mirror(self.root/'owned',rows)
        target.unlink()
        with self.assertRaises(ValueError):m.verify_mirror(self.root/'owned',rows)
    def test_fifo_symlink_and_large_read_refused(self):
        path=self.root/'fifo';os.mkfifo(path)
        with self.assertRaises(ValueError):m.stable_bytes(path,100)
        path=self.root/'link';path.symlink_to(self.root/'fifo')
        with self.assertRaises(ValueError):m.stable_bytes(path,100)
        path=self.root/'bytes';path.write_bytes(b'12345')
        with self.assertRaises(ValueError):m.stable_bytes(path,4)
    def test_json_duplicates_nonfinite_and_wrong_maven_paths(self):
        for data in [b'{"v":1,"v":2}',b'{"v":NaN}',b'\xff']:
            with self.assertRaises((ValueError,UnicodeError)):m.strict_json(data)
        for part in ['../bad','bad/name','',None]:
            with self.assertRaises(ValueError):m.relative_path('g',part,'1','pom')

if __name__=='__main__':unittest.main()
