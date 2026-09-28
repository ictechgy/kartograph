package edge.mvc

import org.springframework.core.annotation.AliasFor
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@RequestMapping(method = [RequestMethod.GET], headers = ["X-Edge=1"])
annotation class KGet(
    @get:AliasFor(annotation = RequestMapping::class, attribute = "path")
    val path: Array<String> = [],
)
