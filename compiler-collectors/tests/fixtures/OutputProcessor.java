package fixture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.StandardLocation;

@SupportedAnnotationTypes("*")
@SupportedOptions("fixture.mode")
public final class OutputProcessor extends AbstractProcessor {
    private boolean emitted;
    @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (emitted || round.processingOver()) return false;
        emitted = true;
        String mode = processingEnv.getOptions().getOrDefault("fixture.mode", "normal");
        try {
            var writer = processingEnv.getFiler().createSourceFile("fixture.Generated").openWriter();
            writer.write(mode.equals("broken") ? "not valid Java !!!" : "package fixture; public class Generated { public static int value(){return 7;} }");
            if (!mode.equals("unclosed")) writer.close();
            try (var output = processingEnv.getFiler().createClassFile("fixture.OutputBinary").openOutputStream();
                 var input = getClass().getResourceAsStream("/fixture/OutputBinary.class")) { input.transferTo(output); }
            try (var output = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "fixture", "proof.txt").openWriter()) { output.write("processor-resource"); }
            Files.writeString(Path.of(processingEnv.getOptions().get("kartograph.outputs.directRoot"), "direct.txt"), "processor-direct");
        } catch (IOException error) { throw new IllegalStateException("fixture output failed", error); }
        return false;
    }
}
