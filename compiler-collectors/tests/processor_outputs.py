"""JSR-269 Filer 관찰과 실패 컴파일의 증거 거부를 실제 javac로 검증한다."""
import os
from pathlib import Path
import zipfile


def exercise(javac, collector, work, run, encoded, sha256, fingerprint, dagger_classpath):
    root = work / 'processor-outputs'
    root.mkdir()
    source = root / 'SmallProcessor.java'
    source.write_text('''
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import java.util.*;
import java.io.*;
@SupportedAnnotationTypes("*") @SupportedSourceVersion(SourceVersion.RELEASE_17)
@SupportedOptions("mode")
public class SmallProcessor extends AbstractProcessor {
  boolean done;
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
    if (round.processingOver() || done) return false;
    done = true;
    String mode = processingEnv.getOptions().getOrDefault("mode", "source");
    if (mode.equals("error")) { processingEnv.getMessager().printMessage(javax.tools.Diagnostic.Kind.ERROR, "deliberate fixture failure"); return false; }
    if (mode.equals("none")) return false;
    try {
      var output = processingEnv.getFiler().createSourceFile("Created");
      if (mode.equals("unclosed")) { output.openWriter().write("class Created {}"); return false; }
      if (mode.equals("stream")) { try (var stream = output.openOutputStream()) { stream.write("class Created {}".getBytes(java.nio.charset.StandardCharsets.UTF_8)); } }
      else { try (var writer = output.openWriter()) { writer.write("class Created {}"); } }
    } catch (IOException failure) { throw new RuntimeException(failure); }
    return false;
  }
}
''')
    processor_classes = root / 'processor-classes'
    run([javac, '-d', processor_classes, source], root)
    processor_jar = root / 'small-processor.jar'
    with zipfile.ZipFile(processor_jar, 'w') as archive:
        for path in processor_classes.rglob('*.class'):
            archive.write(path, path.relative_to(processor_classes))
    handwritten = root / 'Input.java'
    handwritten.write_text('class Input {} class User_Factory {}')
    token = root / 'input.token'
    token.write_text('a' * 64)
    output = root / 'processor.tsv'
    generated = root / 'generated'
    base = [javac, '-g', '-processorpath', os.pathsep.join(map(str, [collector, processor_jar])),
            '-processor', 'dev.kartograph.collectors.RecordingProcessor',
            f'-Xplugin:KartographEvidence collector=javac-processors root={root} output={output} token={token}',
            '-Akartograph.processor=SmallProcessor', f'-Akartograph.evidence.root={root}',
            f'-Akartograph.evidence.output={output}', f'-Akartograph.evidence.token={token}',
            '-d', root / 'classes', '-s', generated]
    def check(mode, count):
        run(base + [f'-Amode={mode}', handwritten], root)
        rows = output.read_text().splitlines()
        assert not list(root.glob('*.processor-*.tmp')), 'completed compilation left pending stage entries'
        assert rows[0] == 'format\tkartograph-compiler-evidence\t2'
        assert 'processor\t' + encoded('SmallProcessor') in rows
        assert 'processorArtifact\t' + fingerprint(processor_jar) in rows
        values = [row for row in rows if row.startswith('generated\t')]
        assert len(values) == count
        if count:
            assert values == ['generated\t' + encoded('generated/Created.java') + '\t' + sha256(generated / 'Created.java')]
        assert not any('User_Factory' in row for row in values)
        return output.read_bytes()
    first = check('source', 1)
    assert check('source', 1) == first
    check('stream', 1)
    check('none', 0)
    no_round = run(base + ['-proc:none', handwritten], root)
    assert 'recording processor did not publish a round' in no_round.stderr
    assert not output.exists(), 'disabled annotation processing manufactured an empty generation'
    assert not list(root.glob('*.processor-*.tmp'))
    for mode in ['error', 'unclosed']:
        result = __import__('subprocess').run([str(value) for value in base + [f'-Amode={mode}', handwritten]],
            cwd=root, capture_output=True, text=True, timeout=120)
        assert result.returncode != 0
        assert not output.exists(), 'failed processor run published evidence'
        assert not list(root.glob('*.processor-*.tmp')), 'failed compilation left pending stage entries'
    handwritten.write_text('class Input { int broken = ; }')
    run(base + [handwritten], root, expected=1)
    assert not output.exists()

    # 공개 Dagger processor도 같은 Filer 경로로 원본 artifact에 귀속한다.
    dagger = root / 'DaggerInput.java'
    dagger.write_text('import dagger.Component; @Component interface DaggerInput {}')
    dagger_output = root / 'dagger.tsv'
    dagger_generated = root / 'dagger-generated'
    run([javac, '-g', '-cp', dagger_classpath, '-processorpath', str(collector) + os.pathsep + dagger_classpath,
         '-processor', 'dev.kartograph.collectors.RecordingProcessor',
         f'-Xplugin:KartographEvidence collector=javac-processors root={root} output={dagger_output} token={token}',
         '-Akartograph.processor=dagger.internal.codegen.ComponentProcessor', f'-Akartograph.evidence.root={root}',
         f'-Akartograph.evidence.output={dagger_output}', f'-Akartograph.evidence.token={token}',
         '-d', root / 'dagger-classes', '-s', dagger_generated, dagger], root)
    rows = dagger_output.read_text().splitlines()
    assert 'processor\t' + encoded('dagger.internal.codegen.ComponentProcessor') in rows
    assert [r for r in rows if r.startswith('generated\t')] == [
        'generated\t' + encoded('dagger-generated/DaggerDaggerInput.java') + '\t' + sha256(dagger_generated / 'DaggerDaggerInput.java')]
