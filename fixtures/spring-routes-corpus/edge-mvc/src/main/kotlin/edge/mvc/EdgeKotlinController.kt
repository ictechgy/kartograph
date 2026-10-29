package edge.mvc

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(K_BASE)
class EdgeKotlinController {
    @GetMapping("$ITEMS/{id}")
    fun item(): String = ""

    @GetMapping(KotlinRoutes.DEEP, "/two")
    fun deep(): String = ""

    @KGet(["/kget"])
    fun kget(): String = ""

    @PutMapping
    fun put(): String = ""

    companion object {
        const val ITEMS = "/items"
    }
}

interface KotlinApi {
    @GetMapping("/iface/{id}")
    fun iface(id: String): String
}

@RestController
class KotlinApiController : KotlinApi {
    override fun iface(id: String): String = id

    @GetMapping
    fun root(): String = ""
}

abstract class BaseController {
    @GetMapping("/base")
    fun base(): String = ""
}

@RestController
@RequestMapping("/child")
class ChildController : BaseController() {
    @RequestMapping
    fun all(): String = ""
}
