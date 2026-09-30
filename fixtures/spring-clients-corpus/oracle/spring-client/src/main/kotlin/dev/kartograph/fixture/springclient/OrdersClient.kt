package dev.kartograph.fixture.springclient

import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient

/** WebClient 호출이다. base `…/v1/`의 끝 슬래시와 템플릿 앞 슬래시가 겹치면 하나로 준다. */
@Component
class OrdersClient(private val ordersWebClient: WebClient) {
    fun listOrders(): String? = ordersWebClient.get().uri("orders").retrieve().bodyToMono(String::class.java).block()

    fun order(id: Long): String? = ordersWebClient.get().uri("/orders/{id}", id).retrieve().bodyToMono(String::class.java).block()

    fun addItem(id: Long): String? =
        ordersWebClient.post().uri { builder -> builder.path("orders/{id}/items").build(id) }.retrieve().bodyToMono(String::class.java).block()

    fun cancel(id: Long): String? = ordersWebClient.put().uri("/orders/{id}/cancel", mapOf("id" to id)).retrieve().bodyToMono(String::class.java).block()
}
