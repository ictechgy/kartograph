package dev.kartograph.index

import org.objectweb.asm.Opcodes
import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureVisitor

/** descriptor에서 지워진 제네릭 타입도 classfile Signature가 관찰한 의존성으로 보존한다. */
internal fun signatureClassNames(signature: String?, typeOnly: Boolean = false): Set<String> {
    if (signature == null) return emptySet()
    val names = sortedSetOf<String>()
    val visitor = ClassNameSignatureVisitor(names)
    if (typeOnly) SignatureReader(signature).acceptType(visitor) else SignatureReader(signature).accept(visitor)
    return names
}

private class ClassNameSignatureVisitor(private val names: MutableSet<String>, private val depth: Int = 0) : SignatureVisitor(Opcodes.ASM9) {
    private var owner: String? = null
    override fun visitClassType(name: String) { owner = name; names += name }
    override fun visitInnerClassType(name: String) {
        owner = requireNotNull(owner) + "$" + name
        names += requireNotNull(owner)
    }
    private fun child(): SignatureVisitor {
        require(depth < 512) { "generic signature nesting exceeds the supported limit" }
        return ClassNameSignatureVisitor(names, depth + 1)
    }
    override fun visitArrayType(): SignatureVisitor = child()
    override fun visitTypeArgument(wildcard: Char): SignatureVisitor = child()
    override fun visitClassBound(): SignatureVisitor = child()
    override fun visitInterfaceBound(): SignatureVisitor = child()
    override fun visitSuperclass(): SignatureVisitor = child()
    override fun visitInterface(): SignatureVisitor = child()
    override fun visitParameterType(): SignatureVisitor = child()
    override fun visitReturnType(): SignatureVisitor = child()
    override fun visitExceptionType(): SignatureVisitor = child()
}
