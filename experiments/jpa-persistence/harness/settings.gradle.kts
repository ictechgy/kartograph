// JPA 명명 벡터의 Hibernate 실행 오라클이다 — 제품 빌드·기본 CI와 분리된 독립 빌드다.
rootProject.name = "jpa-naming-harness"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

include("hibernate6", "hibernate7-2", "hibernate7-4")
