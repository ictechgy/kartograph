package dev.kartograph.index

/** API selector와 값 전파 구현을 분리한다. 모델은 해당 호출이 있을 때만 적용하며 보존 root를 만들지 않는다. */
internal object RuntimeLibraryModels {
    private data class Signature(val owner: String, val name: String, val descriptor: String, val static: Boolean)
    private val models = buildMap {
        fun add(operation: RuntimeOperation, owner: String, names: List<String>, descriptors: List<String>, static: Boolean = false, sensitive: Boolean = true) {
            val model = RuntimeLibraryModel("jdk.${operation.name.lowercase().replace('_', '-')}.v1", operation, sensitive)
            for (name in names) for (descriptor in descriptors) put(Signature(owner, name, descriptor, static), model)
        }
        add(RuntimeOperation.CLASS_LOADING, "java/lang/Class", listOf("forName"), listOf(
            "(Ljava/lang/String;)Ljava/lang/Class;", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;",
            "(Ljava/lang/Module;Ljava/lang/String;)Ljava/lang/Class;"), static = true)
        add(RuntimeOperation.CONSTRUCTOR_LOOKUP, "java/lang/Class", listOf("getConstructor", "getDeclaredConstructor"), listOf("([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;"))
        add(RuntimeOperation.CLASS_CONSTRUCTION, "java/lang/Class", listOf("newInstance"), listOf("()Ljava/lang/Object;"))
        add(RuntimeOperation.CONSTRUCTOR_INVOCATION, "java/lang/reflect/Constructor", listOf("newInstance"), listOf("([Ljava/lang/Object;)Ljava/lang/Object;"))
        add(RuntimeOperation.METHOD_LOOKUP, "java/lang/Class", listOf("getMethod", "getDeclaredMethod"), listOf("(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"))
        add(RuntimeOperation.FIELD_LOOKUP, "java/lang/Class", listOf("getField", "getDeclaredField"), listOf("(Ljava/lang/String;)Ljava/lang/reflect/Field;"))
        add(RuntimeOperation.METHOD_INVOCATION, "java/lang/reflect/Method", listOf("invoke"), listOf("(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"))
        for ((suffix, type) in listOf("" to "Ljava/lang/Object;", "Boolean" to "Z", "Byte" to "B", "Char" to "C", "Short" to "S", "Int" to "I", "Long" to "J", "Float" to "F", "Double" to "D")) {
            add(RuntimeOperation.FIELD_ACCESS, "java/lang/reflect/Field", listOf("get$suffix"), listOf("(Ljava/lang/Object;)$type"))
            add(RuntimeOperation.FIELD_ACCESS, "java/lang/reflect/Field", listOf("set$suffix"), listOf("(Ljava/lang/Object;$type)V"))
        }
        add(RuntimeOperation.SERVICE_LOADING, "java/util/ServiceLoader", listOf("load"), listOf(
            "(Ljava/lang/Class;)Ljava/util/ServiceLoader;", "(Ljava/lang/Class;Ljava/lang/ClassLoader;)Ljava/util/ServiceLoader;",
            "(Ljava/lang/ModuleLayer;Ljava/lang/Class;)Ljava/util/ServiceLoader;"), static = true)
        add(RuntimeOperation.SERVICE_LOADING, "java/util/ServiceLoader", listOf("loadInstalled"), listOf("(Ljava/lang/Class;)Ljava/util/ServiceLoader;"), static = true)
        add(RuntimeOperation.CLASS_NAME, "java/lang/Class", listOf("getName"), listOf("()Ljava/lang/String;"), sensitive = false)
        add(RuntimeOperation.STRING_CONCAT, "java/lang/String", listOf("concat"), listOf("(Ljava/lang/String;)Ljava/lang/String;"), sensitive = false)
        add(RuntimeOperation.STRING_VALUE, "java/lang/String", listOf("valueOf"), listOf("Z", "C", "I", "J", "F", "D", "Ljava/lang/Object;", "[C").map { "($it)Ljava/lang/String;" }, static = true, sensitive = false)
    }

    fun find(owner: String, name: String, descriptor: String, static: Boolean, isClassLoader: (String) -> Boolean): RuntimeLibraryModel? =
        models[Signature(owner, name, descriptor, static)] ?: when {
            !static && name == "loadClass" && descriptor in LOADER_DESCRIPTORS && isClassLoader(owner) ->
                RuntimeLibraryModel("jdk.class-loader.v1", RuntimeOperation.CLASS_LOADER, true)
            !static && name == "getClass" && descriptor == "()Ljava/lang/Class;" ->
                RuntimeLibraryModel("jdk.object-class.v1", RuntimeOperation.OBJECT_CLASS, false)
            else -> null
        }

    private val LOADER_DESCRIPTORS = setOf("(Ljava/lang/String;)Ljava/lang/Class;", "(Ljava/lang/String;Z)Ljava/lang/Class;")
}

internal data class RuntimeLibraryModel(val id: String, val operation: RuntimeOperation, val sensitive: Boolean)
internal enum class RuntimeOperation {
    CLASS_LOADING, CLASS_LOADER, CONSTRUCTOR_LOOKUP, CLASS_CONSTRUCTION, CONSTRUCTOR_INVOCATION,
    METHOD_LOOKUP, FIELD_LOOKUP, METHOD_INVOCATION, FIELD_ACCESS, SERVICE_LOADING,
    CLASS_NAME, OBJECT_CLASS, STRING_CONCAT, STRING_VALUE,
}
