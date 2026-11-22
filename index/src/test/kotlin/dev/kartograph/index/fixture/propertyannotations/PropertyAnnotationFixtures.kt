package dev.kartograph.index.fixture.propertyannotations

/** getter만 있는 property에 붙는다. compiler는 property 대상 어노테이션을 합성 method `getComputed$annotations`에 둔다. */
annotation class OnGetterOnly

/** setter로만 쓰이는(backing field 없는) property에 붙는다. */
annotation class OnWriteOnly

/** getter 없이 backing field로만 읽는 private property에 붙는다. */
annotation class OnPrivateField

/** top-level property에 붙는다. 합성 method는 file facade에 생긴다. */
annotation class OnTopLevel

/** interface property에 붙는다. 기본 `-jvm-default` 설정에서 합성 method는 `Sized$DefaultImpls`에 생긴다. */
annotation class OnInterface

/** 읽지 않는 getter 전용 property에 붙는다. property가 쓰이지 않으므로 계속 보고되어야 한다. */
annotation class OnUnreadGetter

/** 도달하지 않는 class의 property에 붙는다. 계속 보고되어야 한다. */
annotation class OnUnreachableOwner

/** 어노테이션을 단 top-level property다. */
@OnTopLevel
val topLevel: Int get() = 1

/** 어노테이션을 단 interface property다. */
interface Sized {
    /** 기본 구현이 있는 interface property다. */
    @OnInterface
    val size: Int get() = 0
}

/** property 형태별 어노테이션을 담는다. */
class Holder : Sized {
    /** getter만 있는 property다. */
    @OnGetterOnly
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

/** `unread`를 뺀 property를 쓰는 호출자다. */
fun useProperties(holder: Holder): Int {
    holder.sink = 1
    val sized: Sized = holder
    return topLevel + sized.size + holder.computed + holder.reveal()
}
