package edge.mvc

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.ServerResponse
import org.springframework.web.servlet.function.router

@Configuration
class Router {
    @Bean
    fun routes() = router {
        GET("/fn") { ServerResponse.ok().body("fn") }
    }
}
