package dev.kartograph.collectors;

import java.io.File;
import org.jetbrains.kotlin.backend.jvm.ir.JvmIrUtilsKt;
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar;
import org.jetbrains.kotlin.compiler.plugin.FirExtensionRegistrarConfigurationUtilKt;
import org.jetbrains.kotlin.extensions.ExtensionPointDescriptor;
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar;
import org.jetbrains.kotlin.ir.declarations.IrDeclaration;
import org.jetbrains.kotlin.ir.declarations.IrFile;

/** Kotlin source facade와 compiler extension 등록의 Java facade bridge이다. */
public final class KotlinCompilerBridge {
    private KotlinCompilerBridge() {}

    public static File ioFile(IrFile file) { return JvmIrUtilsKt.getIoFile(file); }

    public static File ioFileForDeclaration(IrDeclaration declaration) {
        IrFile file = JvmIrUtilsKt.getFileParentOrNull(declaration);
        return file == null ? null : JvmIrUtilsKt.getIoFile(file);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void register(
        CompilerPluginRegistrar.ExtensionStorage storage,
        ExtensionPointDescriptor<?> descriptor,
        Object extension
    ) {
        storage.registerExtension((ExtensionPointDescriptor) descriptor, extension);
    }

    public static void registerFir(CompilerPluginRegistrar.ExtensionStorage storage, FirExtensionRegistrar extension) {
        FirExtensionRegistrarConfigurationUtilKt.registerExtension(
            storage,
            FirExtensionRegistrar.Companion,
            extension
        );
    }
}
