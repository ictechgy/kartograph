package dev.kartograph.collectors;

import java.io.FilterOutputStream;
import java.io.FilterWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.annotation.processing.Completion;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileObject;
import javax.tools.JavaFileManager.Location;
import javax.tools.JavaFileObject;

/** 명시적으로 지정한 JSR-269 processor의 Filer source 생성만 관찰하는 opt-in adapter다. */
public final class RecordingProcessor implements Processor {
    private static final String PREFIX = "kartograph.evidence.";
    private Processor delegate;
    private EvidenceProtocol.Options options;
    private String artifact, processorArtifact;
    private final Map<String, EvidenceProtocol.Source> generated = new TreeMap<>();
    private final Set<Path> unclosed = new HashSet<>();

    @Override public void init(ProcessingEnvironment environment) {
        if (delegate != null) throw new IllegalStateException("recording processor was initialized twice");
        Map<String, String> values = environment.getOptions();
        String identity = values.get("kartograph.processor");
        if (identity == null || identity.equals(getClass().getName())) throw new IllegalArgumentException("select one delegate using -Akartograph.processor");
        options = EvidenceProtocol.parse("javac-processors", "root=" + required(values, "root"),
            "output=" + required(values, "output"), "token=" + required(values, "token"));
        artifact = EvidenceProtocol.artifactFingerprint(getClass());
        ProcessorEvidenceFiles.requireRequest(options, artifact);
        try {
            Class<?> type = Class.forName(identity, true, getClass().getClassLoader());
            if (!Processor.class.isAssignableFrom(type)) throw new IllegalArgumentException("delegate is not a JSR-269 processor");
            delegate = (Processor) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException error) { throw new IllegalArgumentException("delegate processor could not be initialized", error); }
        processorArtifact = EvidenceProtocol.artifactFingerprint(delegate.getClass());
        delegate.init(new ProcessingEnvironment() {
            @Override public Map<String, String> getOptions() { return environment.getOptions(); }
            @Override public Messager getMessager() { return environment.getMessager(); }
            @Override public Filer getFiler() { return new RecordingFiler(environment.getFiler()); }
            @Override public Elements getElementUtils() { return environment.getElementUtils(); }
            @Override public Types getTypeUtils() { return environment.getTypeUtils(); }
            @Override public SourceVersion getSourceVersion() { return environment.getSourceVersion(); }
            @Override public Locale getLocale() { return environment.getLocale(); }
            @Override public boolean isPreviewEnabled() { return environment.isPreviewEnabled(); }
        });
    }
    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        boolean claimed = delegate.process(annotations, round);
        if (round.processingOver() && !round.errorRaised()) {
            if (!unclosed.isEmpty()) throw new IllegalArgumentException("processor left generated sources unclosed");
            ProcessorEvidenceFiles.write(options, artifact,
                new EvidenceProtocol.Generation(delegate.getClass().getName(), processorArtifact, generated.values()));
        }
        return claimed;
    }
    @Override public Set<String> getSupportedOptions() {
        Set<String> values = new HashSet<>(delegate == null ? Set.of() : delegate.getSupportedOptions());
        values.addAll(Set.of("kartograph.processor", PREFIX + "root", PREFIX + "output", PREFIX + "token"));
        return Set.copyOf(values);
    }
    @Override public Set<String> getSupportedAnnotationTypes() { return delegate == null ? Set.of("*") : delegate.getSupportedAnnotationTypes(); }
    @Override public SourceVersion getSupportedSourceVersion() { return delegate == null ? SourceVersion.latestSupported() : delegate.getSupportedSourceVersion(); }
    @Override public Iterable<? extends Completion> getCompletions(Element element, AnnotationMirror annotation, ExecutableElement member, String text) {
        return delegate.getCompletions(element, annotation, member, text);
    }
    private static String required(Map<String, String> values, String key) {
        String value = values.get(PREFIX + key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("processor evidence root, output and token are required");
        return value;
    }
    private void closed(Path path) {
        EvidenceProtocol.Source source = EvidenceProtocol.source(options.root(), path);
        EvidenceProtocol.Source previous = generated.putIfAbsent(source.path(), source);
        if (previous != null && !previous.equals(source)) throw new IllegalArgumentException("processor source changed after closing");
        unclosed.remove(path);
    }
    private final class RecordingFiler implements Filer {
        private final Filer filer;
        RecordingFiler(Filer filer) { this.filer = filer; }
        @Override public JavaFileObject createSourceFile(CharSequence name, Element... origins) throws IOException {
            if (generated.size() + unclosed.size() >= 100_000) throw new IllegalArgumentException("processor output count exceeds limit");
            JavaFileObject output = filer.createSourceFile(name, origins);
            Path path = Path.of(output.toUri()).toFile().getCanonicalFile().toPath();
            if (!path.startsWith(options.root().toAbsolutePath().normalize())) throw new IllegalArgumentException("processor output is outside project root");
            unclosed.add(path);
            return new ForwardingJavaFileObject<JavaFileObject>(output) {
                @Override public OutputStream openOutputStream() throws IOException {
                    return new FilterOutputStream(super.openOutputStream()) {
                        @Override public void close() throws IOException { super.close(); closed(path); }
                    };
                }
                @Override public Writer openWriter() throws IOException {
                    return new FilterWriter(super.openWriter()) {
                        @Override public void close() throws IOException { super.close(); closed(path); }
                    };
                }
            };
        }
        // class/resource 출력은 source 생성으로 추측하지 않는다.
        @Override public JavaFileObject createClassFile(CharSequence name, Element... origins) throws IOException { return filer.createClassFile(name, origins); }
        @Override public FileObject createResource(Location location, CharSequence module, CharSequence name, Element... origins) throws IOException { return filer.createResource(location, module, name, origins); }
        @Override public FileObject getResource(Location location, CharSequence module, CharSequence name) throws IOException { return filer.getResource(location, module, name); }
    }
}
