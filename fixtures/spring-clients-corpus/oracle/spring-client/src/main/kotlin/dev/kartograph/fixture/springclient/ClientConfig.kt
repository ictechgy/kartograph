package dev.kartograph.fixture.springclient

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.support.RestClientAdapter
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.service.invoker.HttpServiceProxyFactory

/** 클라이언트 빈 구성이다 — base는 `@Value` 설정, 리터럴, 실행 시점 값에서 온다. */
@Configuration
class ClientConfig {
    /** 기본 프로필 설정의 base로 만든 RestClient다. */
    @Bean
    fun usersRestClient(builder: RestClient.Builder, @Value("\${users.base-url}") baseUrl: String): RestClient =
        builder.baseUrl(baseUrl).defaultHeader("X-Client", "oracle").build()

    /** 다른 프로필이 base를 바꾸는 RestClient다. */
    @Bean
    fun searchRestClient(builder: RestClient.Builder, @Value("\${search.url}") searchUrl: String): RestClient =
        builder.baseUrl(searchUrl).build()

    /** 끝 슬래시가 있는 리터럴 base의 WebClient다. */
    @Bean
    fun ordersWebClient(builder: WebClient.Builder): WebClient = builder.baseUrl("http://orders.example.test/v1/").build()

    /** Spring Boot `rootUri`로 만든 RestTemplate이다. */
    @Bean
    fun billingRestTemplate(builder: RestTemplateBuilder, @Value("\${billing.root}") root: String): RestTemplate =
        builder.rootUri(root).build()

    /** base가 실행 시점 환경 값이라 정적으로 풀 수 없는 RestClient다. */
    @Bean
    fun envRestClient(): RestClient = RestClient.create(System.getenv("ORACLE_ENV_BASE") ?: "http://env.example.test/e")

    /** `@HttpExchange` 인터페이스를 RestClient 어댑터로 만든다. */
    @Bean
    fun catalogApi(): CatalogApi {
        val client = RestClient.builder().baseUrl("http://catalog.example.test/shop").build()
        val factory = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build()
        return factory.createClient(CatalogApi::class.java)
    }
}
