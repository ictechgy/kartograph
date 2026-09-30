package dev.kartograph.e2e.users

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 서비스 B(사용자) 앱이다. */
@SpringBootApplication
class UsersApplication

/** 사용자 조회 route다. */
@RestController
@RequestMapping("/api")
class UsersController(private val directory: UserDirectory) {
    @GetMapping("/users/{id}")
    fun user(@PathVariable id: String): String = directory.find(id)
}

/** 핸들러가 부르는 도메인 계층이다(정방향 도달 확인용). */
@Component
class UserDirectory {
    fun find(id: String): String = "user-$id"
}
