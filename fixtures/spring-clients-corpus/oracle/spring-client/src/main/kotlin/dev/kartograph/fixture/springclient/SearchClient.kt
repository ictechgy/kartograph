package dev.kartograph.fixture.springclient

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/** 다른 프로필이 base를 바꾸는 클라이언트다(오라클은 기본 프로필로 실행한다). */
@Component
class SearchClient(@Qualifier("searchRestClient") private val client: RestClient) {
    fun search(term: String): String? = client.get().uri("/query/{term}", term).retrieve().body(String::class.java)
}
