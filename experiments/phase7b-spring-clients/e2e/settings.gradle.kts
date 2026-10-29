// 두 서비스 e2e(서비스 A의 핸들러 → RestClient → 서비스 B의 route)다 — 제품 빌드·기본 CI와 분리된 독립 빌드다.
rootProject.name = "spring-two-service-e2e"
include("users-service", "orders-service")

dependencyResolutionManagement {
    repositories { mavenCentral() }
}
