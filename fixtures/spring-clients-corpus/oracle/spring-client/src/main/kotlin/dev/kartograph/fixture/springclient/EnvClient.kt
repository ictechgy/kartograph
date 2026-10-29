package dev.kartograph.fixture.springclient

import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/** base가 실행 시점 값인 클라이언트다 — 생산자는 base 앵커와 빈 선언의 baseRef를 낸다. */
@Component
class EnvClient(private val envRestClient: RestClient) {
    fun ping(): String? = envRestClient.get().uri("/ping").retrieve().body(String::class.java)
}
