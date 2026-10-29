package dev.kartograph.fixture.springclient

import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/** RestClient 호출이다. 같은 타입 빈이 여럿이라 매개변수 이름(`usersRestClient`)으로 주입된다. */
@Component
class UserClient(private val usersRestClient: RestClient) {
    fun fetchUser(id: String): String? = usersRestClient.get().uri("/users/{id}", id).retrieve().body(String::class.java)

    fun activeUsers(): String? = usersRestClient.get().uri("/users?active={active}", true).retrieve().body(String::class.java)

    fun status(): String? = usersRestClient.get().uri("status").retrieve().body(String::class.java)

    fun deleteUser(id: String): String? =
        usersRestClient.delete().uri { it.path("/users/{id}").queryParam("hard", true).build(id) }.retrieve().body(String::class.java)

    fun bulkPatch(): String? = usersRestClient.method(HttpMethod.PATCH).uri("/users//bulk/").retrieve().body(String::class.java)

    fun audit(day: String): String? = usersRestClient.get().uri("http://audit.example.test/log/{day}", day).retrieve().body(String::class.java)

    fun dotted(): String? = usersRestClient.get().uri("/users/./me/../self").retrieve().body(String::class.java)
}
