package dev.kartograph.e2e.orders

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient

/** 서비스 A(주문) 앱이다. */
@SpringBootApplication
class OrdersApplication

/** 서비스 B를 부르는 RestClient 빈이다. */
@Configuration
class ClientConfig {
    @Bean
    fun usersRestClient(builder: RestClient.Builder, @Value("\${users.base-url}") baseUrl: String): RestClient =
        builder.baseUrl(baseUrl).build()
}

/** 서비스 B 호출 지점이다. */
@Component
class UserGateway(private val usersRestClient: RestClient) {
    fun fetchUser(id: String): String? = usersRestClient.get().uri("/users/{id}", id).retrieve().body(String::class.java)
}

/** 핸들러와 호출 지점 사이의 서비스 계층이다. */
@Service
class OrderService(private val users: UserGateway) {
    fun describe(orderId: String): String = "order $orderId for ${users.fetchUser("u-$orderId")}"
}

/** 서비스 A의 route다. */
@RestController
class OrdersController(private val orders: OrderService) {
    @GetMapping("/orders/{id}")
    fun order(@PathVariable id: String): String = orders.describe(id)
}
