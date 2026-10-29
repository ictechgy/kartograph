package dev.kartograph.fixture.springclient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/** Java 빈 구성이다 — `.properties` 설정의 base, 이름 붙은 빈, WebClient 어댑터의 `@HttpExchange`. */
@Configuration
public class InventoryConfig {
    /** 이름으로 주입되는 RestClient다. */
    @Bean("inventory")
    public RestClient inventoryClient(RestClient.Builder builder, @Value("${inventory.url}") String url) {
        return builder.baseUrl(url).build();
    }

    /** WebClient 어댑터로 만든 `@HttpExchange` 클라이언트다. */
    @Bean
    public ReportsApi reportsApi() {
        WebClient webClient = WebClient.builder().baseUrl("http://reports.example.test/r/").build();
        return HttpServiceProxyFactory.builderFor(WebClientAdapter.create(webClient)).build().createClient(ReportsApi.class);
    }
}
