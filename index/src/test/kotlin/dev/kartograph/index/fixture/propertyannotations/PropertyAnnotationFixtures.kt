package dev.kartograph.index.fixture.propertyannotations

import kotlin.reflect.KClass

/** getter만 있는 property에 붙는다. compiler는 property 대상 어노테이션을 합성 method `getComputed$annotations`에 둔다. */
annotation class OnGetterOnly

/** setter로만 쓰이는(backing field 없는) property에 붙는다. */
annotation class OnWriteOnly

/** getter 없이 backing field로만 읽는 private property에 붙는다. */
annotation class OnPrivateField

/** top-level property에 붙는다. 합성 method는 file facade에 생긴다. */
annotation class OnTopLevel

/** extension property에 붙는다. 합성 method의 descriptor에도 receiver 형식이 있다. */
annotation class OnExtension

/** class 참조 값을 가진 property 어노테이션이다. */
annotation class WithTarget(val target: KClass<*>)

/** property 어노테이션의 값으로만 참조되는 class다. property가 쓰이면 도달한다. */
class OnlyInPropertyValue

/** interface property에 붙는다. 기본 `-jvm-default` 설정에서 합성 method는 `Sized$DefaultImpls`에 생긴다. */
annotation class OnInterface

/** 읽지 않는 getter 전용 property에 붙는다. property가 쓰이지 않으므로 계속 보고되어야 한다. */
annotation class OnUnreadGetter

/** 도달하지 않는 class의 property에 붙는다. 계속 보고되어야 한다. */
annotation class OnUnreachableOwner

/** `DefaultImpls`에 합성 method가 있는 interface의 읽는 abstract property에 붙는다. */
annotation class OnGaugeUsed

/** 같은 interface의 읽지 않는 sibling property에 붙는다. 계속 보고되어야 한다. */
annotation class OnGaugeUnusedSibling

/** 쓰이지 않는 interface default 본문에서만 만드는 class다. `DefaultImpls`를 통해 도달하면 안 된다. */
class OnlyInUnusedGaugeDefault {
    /** default 본문이 부른다. */
    fun go(): Int = 7
}

/** `-jvm-default` 호환 mode(이 build의 기본)라 property 합성 method와 default 본문 사본이 `Gauge$DefaultImpls`에 생긴다. */
interface Gauge {
    /** 읽는 abstract property다. */
    @OnGaugeUsed
    val used: Int

    /** 읽지 않는 sibling property다. */
    @OnGaugeUnusedSibling
    val unusedSibling: Int get() = 3

    /** 아무도 부르지 않는 default 본문이다. */
    fun unusedDefault(): Int = OnlyInUnusedGaugeDefault().go()
}

/** [Gauge]의 구현이다. */
class GaugeImpl : Gauge {
    override val used: Int = 1
}

/** 호환 사본 없이 컴파일된 interface의 읽는 property에 붙는다. 합성 method는 interface 자신에 생긴다. */
annotation class OnPlainUsed

/** 같은 interface의 읽지 않는 sibling property에 붙는다. 계속 보고되어야 한다. */
annotation class OnPlainUnusedSibling

/** `@JvmDefaultWithoutCompatibility` interface의 쓰이지 않는 default 본문에서만 만드는 class다. */
class OnlyInUnusedPlainDefault {
    /** default 본문이 부른다. */
    fun go(): Int = 8
}

/** `DefaultImpls` 없이 컴파일되는 interface다(`-jvm-default=no-compatibility`와 같은 배치). */
@JvmDefaultWithoutCompatibility
interface Plain {
    /** 읽는 property다. */
    @OnPlainUsed
    val plainUsed: Int get() = 4

    /** 읽지 않는 sibling property다. */
    @OnPlainUnusedSibling
    val plainUnusedSibling: Int get() = 5

    /** 아무도 부르지 않는 default 본문이다. */
    fun plainUnusedDefault(): Int = OnlyInUnusedPlainDefault().go()
}

/** [Plain]의 구현이다. */
class PlainImpl : Plain

/** 어노테이션을 단 top-level property다. */
@OnTopLevel
val topLevel: Int get() = 1

/** 어노테이션을 단 top-level extension property다. */
@OnExtension
val Holder.twice: Int get() = computed * 2

/** 어노테이션을 단 interface property다. */
interface Sized {
    /** 기본 구현이 있는 interface property다. */
    @OnInterface
    val size: Int get() = 0
}

/** property 형태별 어노테이션을 담는다. */
class Holder : Sized {
    /** getter만 있는 property다. 값으로 class를 참조하는 어노테이션도 단다. */
    @OnGetterOnly
    @WithTarget(OnlyInPropertyValue::class)
    val computed: Int get() = 2

    /** setter로만 쓰는 property다. */
    @OnWriteOnly
    var sink: Int
        get() = 0
        set(value) { require(value >= 0) }

    /** accessor가 생성되지 않는 private property다. */
    @OnPrivateField
    private val secret: Int = 3

    /** 읽지 않는 property다. */
    @OnUnreadGetter
    val unread: Int get() = 4

    /** private property를 field로 읽는다. */
    fun reveal(): Int = secret
}

/** 아무도 만들지 않는 class다. */
class Unreachable {
    /** 도달하지 않는 owner의 property다. */
    @OnUnreachableOwner
    val value: Int get() = 5
}

/** `unread`·sibling property·default 본문을 뺀 property를 쓰는 호출자다. */
fun useProperties(holder: Holder, gauge: Gauge, plain: Plain): Int {
    holder.sink = 1
    val sized: Sized = holder
    return topLevel + holder.twice + sized.size + holder.computed + holder.reveal() + gauge.used + plain.plainUsed
}
