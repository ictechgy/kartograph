package dev.kartograph.fixture.springclient;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 생성자 주입 RestClient 호출이다. */
@Component
public class InventoryClient {
    private final RestClient client;

    public InventoryClient(@Qualifier("inventory") RestClient client) {
        this.client = client;
    }

    public String stock(String sku) {
        return client.get().uri("/stock/{sku}", sku).retrieve().body(String.class);
    }

    public String reserve(String sku) {
        return client.post().uri(uriBuilder -> uriBuilder.path("reservations/{sku}").build(sku)).retrieve().body(String.class);
    }
}
