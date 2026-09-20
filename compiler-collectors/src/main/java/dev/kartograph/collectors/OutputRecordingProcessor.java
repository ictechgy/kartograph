package dev.kartograph.collectors;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.*;

/** javac/KAPT의 명시적 단일 processor를 감싸 source·class·resource Filer 출력을 관찰한다. */
public final class OutputRecordingProcessor implements Processor {
    private Processor delegate;
    private ProcessorOutputSession session;
    @Override public void init(ProcessingEnvironment environment) {
        var options = environment.getOptions();
        String identity = options.get("kartograph.processor");
        if (identity == null || identity.equals(getClass().getName())) throw new IllegalArgumentException("select one delegate processor");
        try { delegate = (Processor) Class.forName(identity).getDeclaredConstructor().newInstance(); }
        catch (ReflectiveOperationException error) { throw new IllegalArgumentException("processor delegate is unavailable", error); }
        session = new ProcessorOutputSession(options, options.getOrDefault("kartograph.outputs.kind", "javac"), delegate.getClass(), getClass());
        session.beginCallback();
        delegate.init(new ProcessingEnvironment() {
            @Override public Map<String, String> getOptions() { return options; }
            @Override public Messager getMessager() { return environment.getMessager(); }
            @Override public Filer getFiler() { return new RecordingFiler(environment.getFiler()); }
            @Override public Elements getElementUtils() { return environment.getElementUtils(); }
            @Override public Types getTypeUtils() { return environment.getTypeUtils(); }
            @Override public SourceVersion getSourceVersion() { return environment.getSourceVersion(); }
            @Override public Locale getLocale() { return environment.getLocale(); }
            @Override public boolean isPreviewEnabled() { return environment.isPreviewEnabled(); }
        });
        session.endCallback();
    }
    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        session.beginCallback();
        boolean claimed = delegate.process(annotations, round);
        session.endCallback();
        if (round.processingOver() && !round.errorRaised()) session.finish();
        return claimed;
    }
    @Override public Set<String> getSupportedOptions() {
        var values = new HashSet<>(delegate == null ? Set.<String>of() : delegate.getSupportedOptions());
        values.add("kartograph.processor");
        for (String key : Set.of("root", "output", "token", "kind", "directRoot")) values.add("kartograph.outputs." + key);
        return values;
    }
    @Override public Set<String> getSupportedAnnotationTypes() { return delegate == null ? Set.of("*") : delegate.getSupportedAnnotationTypes(); }
    @Override public SourceVersion getSupportedSourceVersion() { return delegate == null ? SourceVersion.latestSupported() : delegate.getSupportedSourceVersion(); }
    @Override public Iterable<? extends Completion> getCompletions(Element e, AnnotationMirror a, ExecutableElement m, String text) { return delegate.getCompletions(e, a, m, text); }
    private final class RecordingFiler implements Filer {
        private final Filer filer;
        RecordingFiler(Filer filer) { this.filer = filer; }
        private JavaFileObject javaOutput(JavaFileObject output, String kind) {
            Path path = Path.of(output.toUri()); session.opened(path);
            return new ForwardingJavaFileObject<JavaFileObject>(output) {
                @Override public OutputStream openOutputStream() throws IOException { return session.stream(super.openOutputStream(), path, kind); }
                @Override public Writer openWriter() throws IOException { return session.writer(super.openWriter(), path, kind); }
            };
        }
        @Override public JavaFileObject createSourceFile(CharSequence name, Element... origins) throws IOException { return javaOutput(filer.createSourceFile(name, origins), "source"); }
        @Override public JavaFileObject createClassFile(CharSequence name, Element... origins) throws IOException { return javaOutput(filer.createClassFile(name, origins), "class"); }
        @Override public FileObject createResource(JavaFileManager.Location location, CharSequence module, CharSequence name, Element... origins) throws IOException {
            FileObject output = filer.createResource(location, module, name, origins);
            Path path = Path.of(output.toUri()); session.opened(path);
            return new ForwardingFileObject<FileObject>(output) {
                @Override public OutputStream openOutputStream() throws IOException { return session.stream(super.openOutputStream(), path, "resource"); }
                @Override public Writer openWriter() throws IOException { return session.writer(super.openWriter(), path, "resource"); }
            };
        }
        @Override public FileObject getResource(JavaFileManager.Location location, CharSequence module, CharSequence name) throws IOException { return filer.getResource(location, module, name); }
    }
}
