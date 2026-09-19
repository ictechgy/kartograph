@file:OptIn(kotlin.metadata.ExperimentalContextReceivers::class, kotlin.metadata.ExperimentalAnnotationsInMetadata::class, kotlin.ExperimentalContextParameters::class)

package dev.kartograph.index

import kotlin.Metadata
import kotlin.metadata.KmAnnotation
import kotlin.metadata.KmAnnotationArgument
import kotlin.metadata.KmConstructor
import kotlin.metadata.KmFunction
import kotlin.metadata.KmProperty
import kotlin.metadata.KmType
import kotlin.metadata.KmTypeAlias
import kotlin.metadata.KmTypeParameter
import kotlin.metadata.KmClassifier
import kotlin.metadata.Visibility
import kotlin.metadata.isInline
import kotlin.metadata.visibility
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.annotations
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature
import kotlin.metadata.jvm.syntheticMethodForAnnotations

/** JVM public으로 내려간 Kotlin internal 선언과 실제 공개 Kotlin 타입을 분리한다. */
internal class KotlinDependencyAbi private constructor(private val publishedMembers: Set<String>, private val publishedClass: Boolean) {
    var visible: Boolean = true
    var complete: Boolean = true
    val methods = mutableMapOf<String, Boolean>()
    val fields = mutableMapOf<String, Boolean>()
    val inlineMethods = mutableSetOf<String>()
    val apiTypes = mutableSetOf<String>()
    val allTypes = mutableSetOf<String>()
    val declaredAliases = mutableSetOf<String>()
    val referencedAliases = mutableSetOf<String>()

    private fun types(type: KmType?, destination: MutableSet<String>) {
        if (type == null) return
        val classifier = type.classifier
        // Kotlin built-in 명칭은 JVM class 이름이 아니다. 실제 descriptor가 그 대응 타입을 보존한다.
        if (classifier is KmClassifier.Class && !classifier.name.startsWith("kotlin/")) {
            destination += classifier.name.replace('.', '$')
        }
        if (classifier is KmClassifier.TypeAlias) referencedAliases += classifier.name
        (type.abbreviatedType?.classifier as? KmClassifier.TypeAlias)?.let { referencedAliases += it.name }
        type.annotations.forEach { annotation(it, destination) }
        type.arguments.forEach { types(it.type, destination) }
        types(type.outerType, destination)
        types(type.flexibleTypeUpperBound?.type, destination)
    }

    private fun bounds(parameters: List<KmTypeParameter>, destination: MutableSet<String>) {
        parameters.forEach { parameter ->
            parameter.upperBounds.forEach { types(it, destination) }
            parameter.annotations.forEach { annotation(it, destination) }
        }
    }

    private fun function(function: KmFunction) {
        val public = exposed(function.visibility) || function.signature?.let { it.name + it.descriptor in publishedMembers } == true
        function.signature?.let { signature ->
            val key = signature.name + signature.descriptor
            methods[key] = public
            if (public && function.isInline) inlineMethods += key
        }
        val referenced = mutableSetOf<String>()
        types(function.returnType, referenced)
        types(function.receiverParameterType, referenced)
        (function.valueParameters + function.contextParameters).forEach { types(it.type, referenced); types(it.varargElementType, referenced) }
        legacyContexts(function).forEach { types(it, referenced) }
        function.annotations.forEach { annotation(it, referenced) }
        function.extensionReceiverParameterAnnotations.forEach { annotation(it, referenced) }
        bounds(function.typeParameters, referenced)
        allTypes += referenced
        if (public) apiTypes += referenced
    }

    private fun property(property: KmProperty) {
        val published = property.annotations.any { it.className == "kotlin/PublishedApi" } ||
            property.syntheticMethodForAnnotations?.let { it.name + it.descriptor in publishedMembers } == true
        val public = exposed(property.visibility) || published
        fun accessor(visibility: Visibility): Boolean = exposed(visibility) || (published && visibility == Visibility.INTERNAL)
        property.fieldSignature?.let { fields[it.name + ":" + it.descriptor] = public }
        property.getterSignature?.let {
            methods[it.name + it.descriptor] = public && accessor(property.getter.visibility)
            if (public && accessor(property.getter.visibility) && property.getter.isInline) inlineMethods += it.name + it.descriptor
        }
        property.setterSignature?.let {
            methods[it.name + it.descriptor] = public && property.setter?.let { setter -> accessor(setter.visibility) } == true
            if (public && property.setter?.let { setter -> accessor(setter.visibility) && setter.isInline } == true) inlineMethods += it.name + it.descriptor
        }
        val referenced = mutableSetOf<String>()
        types(property.returnType, referenced)
        types(property.receiverParameterType, referenced)
        property.contextParameters.forEach { types(it.type, referenced) }
        legacyContexts(property).forEach { types(it, referenced) }
        property.annotations.forEach { annotation(it, referenced) }
        property.extensionReceiverParameterAnnotations.forEach { annotation(it, referenced) }
        bounds(property.typeParameters, referenced)
        allTypes += referenced
        if (public) apiTypes += referenced
    }

    private fun constructor(constructor: KmConstructor) {
        val public = exposed(constructor.visibility) || constructor.signature?.let { it.name + it.descriptor in publishedMembers } == true
        constructor.signature?.let { methods[it.name + it.descriptor] = public }
        val referenced = mutableSetOf<String>()
        constructor.valueParameters.forEach { types(it.type, referenced); types(it.varargElementType, referenced) }
        allTypes += referenced
        if (public) apiTypes += referenced
    }

    private fun alias(alias: KmTypeAlias) {
        val referenced = mutableSetOf<String>()
        types(alias.expandedType, referenced)
        alias.annotations.forEach { annotation(it, referenced) }
        bounds(alias.typeParameters, referenced)
        allTypes += referenced
        if (exposed(alias.visibility)) apiTypes += referenced
    }

    private fun annotation(annotation: KmAnnotation, destination: MutableSet<String>) {
        destination += annotation.className.replace('.', '$')
        annotation.arguments.values.forEach { argument(it, destination) }
    }

    private fun argument(value: KmAnnotationArgument, destination: MutableSet<String>) {
        when (value) {
            is KmAnnotationArgument.KClassValue -> destination += value.className.replace('.', '$')
            is KmAnnotationArgument.EnumValue -> destination += value.enumClassName.replace('.', '$')
            is KmAnnotationArgument.AnnotationValue -> annotation(value.annotation, destination)
            is KmAnnotationArgument.ArrayValue -> value.elements.forEach { argument(it, destination) }
            is KmAnnotationArgument.LiteralValue<*> -> Unit
            else -> complete = false
        }
    }

    // 과거 컴파일러가 남긴 context-receiver 메타데이터를 읽기 위한 호환 경로다.
    // 새 모델을 생성하지 않으며 최신 contextParameters도 별도로 읽는다.
    @Suppress("DEPRECATION_ERROR")
    private fun legacyContexts(function: KmFunction): List<KmType> = function.contextReceiverTypes
    @Suppress("DEPRECATION_ERROR")
    private fun legacyContexts(property: KmProperty): List<KmType> = property.contextReceiverTypes
    @Suppress("DEPRECATION_ERROR")
    private fun legacyContexts(klass: kotlin.metadata.KmClass): List<KmType> = klass.contextReceiverTypes

    companion object {
        private fun exposed(visibility: Visibility): Boolean = visibility == Visibility.PUBLIC || visibility == Visibility.PROTECTED

        fun read(annotation: Metadata, owner: String, publishedMembers: Set<String>, publishedClass: Boolean): KotlinDependencyAbi = KotlinDependencyAbi(publishedMembers, publishedClass).apply {
            if (annotation.metadataVersion.firstOrNull()?.let { it > 2 || (it == 2 && annotation.metadataVersion.getOrElse(1) { 0 } > 4) } == true) complete = false
            try {
                when (val value = KotlinClassMetadata.readLenient(annotation)) {
                    is KotlinClassMetadata.Class -> {
                        val klass = value.kmClass
                        visible = exposed(klass.visibility) || publishedClass
                        declaredAliases += klass.typeAliases.map { "${klass.name}.${it.name}" }
                        legacyContexts(klass).forEach { types(it, allTypes); if (visible) types(it, apiTypes) }
                        klass.annotations.forEach { this.annotation(it, allTypes); if (visible) this.annotation(it, apiTypes) }
                        types(klass.inlineClassUnderlyingType, allTypes)
                        if (visible) types(klass.inlineClassUnderlyingType, apiTypes)
                        klass.supertypes.forEach { types(it, allTypes); if (visible) types(it, apiTypes) }
                        bounds(klass.typeParameters, allTypes)
                        if (visible) bounds(klass.typeParameters, apiTypes)
                        klass.functions.forEach(::function)
                        klass.properties.forEach(::property)
                        klass.constructors.forEach(::constructor)
                        klass.typeAliases.forEach(::alias)
                    }
                    is KotlinClassMetadata.FileFacade -> {
                        declaredAliases += value.kmPackage.typeAliases.map { owner.substringBeforeLast('/', "").let { prefix -> if (prefix.isEmpty()) it.name else "$prefix/${it.name}" } }
                        value.kmPackage.functions.forEach(::function)
                        value.kmPackage.properties.forEach(::property)
                        value.kmPackage.typeAliases.forEach(::alias)
                    }
                    is KotlinClassMetadata.MultiFileClassPart -> {
                        declaredAliases += value.kmPackage.typeAliases.map { owner.substringBeforeLast('/', "").let { prefix -> if (prefix.isEmpty()) it.name else "$prefix/${it.name}" } }
                        value.kmPackage.functions.forEach(::function)
                        value.kmPackage.properties.forEach(::property)
                        value.kmPackage.typeAliases.forEach(::alias)
                    }
                    is KotlinClassMetadata.MultiFileClassFacade, is KotlinClassMetadata.SyntheticClass -> visible = false
                    else -> { visible = false; complete = false }
                }
            } catch (_: IllegalArgumentException) {
                visible = false
                complete = false
            }
        }
    }
}
