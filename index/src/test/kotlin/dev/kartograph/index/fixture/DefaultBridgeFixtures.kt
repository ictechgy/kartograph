@file:JvmName("DefaultBridgeFixtures")

package dev.kartograph.index.fixture.defaultbridges

import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

fun String.decorate(prefix: String = "default"): String = "$prefix:$this"
fun callExtensionExplicit(): String = "x".decorate("explicit")
fun callExtensionDefault(): String = "x".decorate()

class Receiver(private val tag: String) {
    fun member(value: Int = 7): String = "$tag:$value"
    fun String.memberExtension(value: Int = 9): String = "$tag:$this:$value"
    fun callMemberExtensionDefault(): String = "inner".memberExtension()
}

fun callInstanceExplicit(receiver: Receiver): String = receiver.member(3)
fun callInstanceDefault(receiver: Receiver): String = receiver.member()

fun overload(value: String): String = "seed:$value"
fun overload(value: String = overload("seed"), count: Int = 2): String = "$value:$count"
fun callOverloadExplicit(): String = overload("explicit", 4)
fun callOverloadDefault(): String = overload()

fun many(
    p01: Int = 1, p02: Int = 2, p03: Int = 3, p04: Int = 4,
    p05: Int = 5, p06: Int = 6, p07: Int = 7, p08: Int = 8,
    p09: Int = 9, p10: Int = 10, p11: Int = 11, p12: Int = 12,
    p13: Int = 13, p14: Int = 14, p15: Int = 15, p16: Int = 16,
    p17: Int = 17, p18: Int = 18, p19: Int = 19, p20: Int = 20,
    p21: Int = 21, p22: Int = 22, p23: Int = 23, p24: Int = 24,
    p25: Int = 25, p26: Int = 26, p27: Int = 27, p28: Int = 28,
    p29: Int = 29, p30: Int = 30, p31: Int = 31, p32: Int = 32,
    p33: Int = 33,
): Int = p01 + p02 + p03 + p04 + p05 + p06 + p07 + p08 + p09 + p10 + p11 +
    p12 + p13 + p14 + p15 + p16 + p17 + p18 + p19 + p20 + p21 + p22 + p23 +
    p24 + p25 + p26 + p27 + p28 + p29 + p30 + p31 + p32 + p33

fun callManyDefault(): Int = many()
fun callManyTailExplicit(): Int = many(p33 = 100)

suspend fun suspendLabel(value: Int = 13): String = suspendCoroutine { continuation ->
    continuation.resume("suspend:$value")
}
suspend fun callSuspendExplicit(): String = suspendLabel(5)
suspend fun callSuspendDefault(): String = suspendLabel()

@JvmName("jvmNamed")
fun sourceNamed(value: Int = 17): String = "named:$value"
fun callNamedExplicit(): String = sourceNamed(6)
fun callNamedDefault(): String = sourceNamed()

fun ordinary(value: String): String = "ordinary:$value"
fun `ordinary$default`(value: String, mask: Int, marker: Any?): String =
    ordinary("$value:$mask:$marker")
fun callAuthoredLookalike(): String = `ordinary$default`("user", 0, null)

fun synthetic(value: String): String = "synthetic:$value"
@JvmSynthetic
fun `synthetic$default`(value: String, mask: Int, marker: Any?): String =
    synthetic("$value:$mask:$marker")
fun callSyntheticLookalike(): String = `synthetic$default`("user", 0, null)

fun namedFake(value: String): String = "renamed:$value"
@JvmSynthetic
@JvmName("namedFake\$default")
fun renamedLookalike(value: String, mask: Int, marker: Any?): String =
    namedFake("$value:$mask:$marker")
fun callRenamedSyntheticLookalike(): String = renamedLookalike("user", 0, null)

@JvmSynthetic
fun `authored$default`(value: String = "nested"): String = "authored:$value"
fun callAuthoredDefault(): String = `authored$default`()

fun thirtyTwo(p01: Int = 1, p02: Int = 2, p03: Int = 3, p04: Int = 4, p05: Int = 5, p06: Int = 6, p07: Int = 7, p08: Int = 8, p09: Int = 9, p10: Int = 10, p11: Int = 11, p12: Int = 12, p13: Int = 13, p14: Int = 14, p15: Int = 15, p16: Int = 16, p17: Int = 17, p18: Int = 18, p19: Int = 19, p20: Int = 20, p21: Int = 21, p22: Int = 22, p23: Int = 23, p24: Int = 24, p25: Int = 25, p26: Int = 26, p27: Int = 27, p28: Int = 28, p29: Int = 29, p30: Int = 30, p31: Int = 31, p32: Int = 32): Int = p01 + p02 + p03 + p04 + p05 + p06 + p07 + p08 + p09 + p10 + p11 + p12 + p13 + p14 + p15 + p16 + p17 + p18 + p19 + p20 + p21 + p22 + p23 + p24 + p25 + p26 + p27 + p28 + p29 + p30 + p31 + p32
fun callThirtyTwo(): Int = thirtyTwo()
