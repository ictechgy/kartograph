package edge.flux

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter
import org.springframework.web.service.annotation.GetExchange
import org.springframework.web.service.annotation.HttpExchange
import org.springframework.web.service.annotation.PostExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@SpringBootApplication
class EdgeFluxApplication

fun main(args: Array<String>) {
    runApplication<EdgeFluxApplication>(*args)
}

const val REACTIVE = "/r"

@RestController
@RequestMapping(REACTIVE, "/reactive")
class ReactiveController {
    @GetMapping("/mono/{id}")
    suspend fun mono(id: String): String = id

    @GetMapping("/flux", produces = ["application/x-ndjson"])
    fun flux(): Flux<String> = Flux.just("a")

    @RequestMapping("/methods", method = [RequestMethod.PUT, RequestMethod.PATCH])
    fun methods(): Mono<String> = Mono.just("m")

    @DeleteMapping("/\${flux.segment}/{id:[0-9]+}")
    fun delete(): Mono<Void> = Mono.empty()

    @PatchMapping("/rest/{*tail}")
    fun tail(): Mono<String> = Mono.just("t")
}

@HttpExchange("/ex")
interface ExchangeApi {
    @GetExchange("/one")
    fun one(): Mono<String>

    @PostExchange
    fun create(): Mono<String>
}

@RestController
class ExchangeController : ExchangeApi {
    override fun one(): Mono<String> = Mono.just("1")

    override fun create(): Mono<String> = Mono.just("c")
}

@Configuration
class FluxRoutes {
    @Bean
    fun fnRoutes() = coRouter {
        GET("/fn") { ServerResponse.ok().bodyValueAndAwait("fn") }
    }
}
