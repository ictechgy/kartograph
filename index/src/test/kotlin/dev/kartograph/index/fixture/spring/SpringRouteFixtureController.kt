package dev.kartograph.index.fixture.spring

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

/** 바이트코드 원천 검증용 controller다. 상수는 다른 파일에 있어 소스만으로는 풀 수 없게 둔다. */
@RestController
@RequestMapping(FixtureRoutes.ITEMS)
class SpringRouteFixtureController {
    /** suspend 핸들러다 — descriptor 끝에 continuation이 붙는다. */
    @GetMapping("/{id:\\d+}", produces = ["application/json"])
    suspend fun item(id: Long): String = id.toString()

    /** companion 상수와 동사 배열이다. */
    @RequestMapping(path = [NESTED], method = [RequestMethod.POST, RequestMethod.PUT])
    fun write(): String = ""

    /** 매핑이 없는 메서드는 핸들러가 아니다. */
    fun helper(): String = ""

    companion object {
        /** companion 상수다. */
        const val NESTED = "/nested"
    }
}
