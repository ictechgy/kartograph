package dev.kartograph.fixture.springclient

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder

/** base 없는 RestTemplate에 `@Value` URL을 연결하거나 `UriComponentsBuilder`로 만든 URL을 넘긴다. */
@Component
class LegacyClient(@Value("\${legacy.url}") private val legacyUrl: String) {
    private val restTemplate = RestTemplate()

    fun item(id: String): String? = restTemplate.getForObject("$legacyUrl/items/{id}", String::class.java, id)

    fun search(term: String): String? {
        val uri = UriComponentsBuilder.fromUriString(legacyUrl).path("/search").queryParam("q", term).build().toUri()
        return restTemplate.getForObject(uri, String::class.java)
    }

    fun segment(id: String): String? =
        restTemplate.getForObject(UriComponentsBuilder.fromHttpUrl(legacyUrl).pathSegment("items", id).toUriString(), String::class.java)
}
