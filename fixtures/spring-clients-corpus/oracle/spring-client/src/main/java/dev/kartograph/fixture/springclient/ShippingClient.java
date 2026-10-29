package dev.kartograph.fixture.springclient;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** base 없는 RestTemplate에 상수 URL을 연결한다. */
@Component
public class ShippingClient {
    private static final String BASE = "http://shipping.example.test/ship";

    private final RestTemplate restTemplate = new RestTemplate();

    public String parcel(String id) {
        return restTemplate.exchange(BASE + "/parcels/{id}", HttpMethod.GET, null, String.class, id).getBody();
    }

    public String createParcel() {
        return restTemplate.postForEntity(BASE + "/parcels", "body", String.class).getBody();
    }
}
