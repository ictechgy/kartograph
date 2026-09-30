package dev.kartograph.fixture.springclient

import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.service.annotation.DeleteExchange
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange

/** `@HttpExchange` 선언형 클라이언트다. 타입 수준 url과 메서드 url은 경계 슬래시가 없을 때만 `/`로 잇는다. */
@HttpExchange("/api")
interface CatalogApi {
    @GetExchange("items/{id}")
    fun item(@PathVariable id: String): String

    @PostExchange("/items")
    fun createItem(@RequestBody body: String): String

    @HttpExchange(method = "PUT", url = "items/{id}/stock")
    fun updateStock(@PathVariable id: String, @RequestBody body: String): String

    @DeleteExchange(url = "/items/{id}")
    fun deleteItem(@PathVariable id: String): String
}
