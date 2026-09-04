package dev.kartograph.fixture

import androidx.annotation.Keep
import dev.kartograph.fixture.library.ExternalRuleIntermediate

@Keep
class KeptByAnnotation

class ActuallyUnused

class KeptByRule

class KeptByWildcardRule

open class RuleBase

class KeptByExtendsRule : RuleBase()

class KeptByClasspathRule : ExternalRuleIntermediate()

class KeptByConsumerRule

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class RuleMarker

@RuleMarker
class KeptByRuleAnnotation

class KeptByMemberAnnotation {
    @Keep
    fun retainedEntryPoint() = Unit
}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class RuleMemberMarker

class ConditionalKept {
    @RuleMemberMarker
    fun reflectedEntryPoint() = Unit
}

class KeptByFinalRule

open class OpenFinalRule

class KeptWithAllMembers {
    fun createDependency() = OnlyCalledFromKeptMember()
}

class OnlyCalledFromKeptMember

class KeptByInject @javax.inject.Inject constructor()

@kotlinx.serialization.Serializable
class KeptBySerializable

@Keep
class KeptClassWithMembers {
    fun createDependency() = OnlyCalledFromKeptClassMember()
}

class OnlyCalledFromKeptClassMember

class SignatureKept {
    fun onEvent(value: String) = value.length
}

class PlainSignatureKept
