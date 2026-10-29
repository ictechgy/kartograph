package dev.kartograph.fixture.springclient;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PatchExchange;

/** Java `@HttpExchange` 인터페이스다(타입 수준 url 없음). */
public interface ReportsApi {
    @GetExchange("daily/{day}")
    String daily(@PathVariable String day);

    @PatchExchange(value = "/monthly/{month}")
    String reopen(@PathVariable String month);
}
