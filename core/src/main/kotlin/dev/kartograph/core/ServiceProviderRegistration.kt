package dev.kartograph.core

/** 입력 artifact의 META-INF/services에 선언된 provider 사실이다. */
public data class ServiceProviderRegistration(
    val service: String,
    val provider: NodeId,
    val location: SourceLocation,
) : Comparable<ServiceProviderRegistration> {
    /** 같은 provider가 여러 입력에 선언되어도 각각의 근거를 정렬해 보존한다. */
    override fun compareTo(other: ServiceProviderRegistration): Int = compareValuesBy(this, other,
        ServiceProviderRegistration::service, ServiceProviderRegistration::provider,
        { it.location.path }, { it.location.line ?: 0 })
}
