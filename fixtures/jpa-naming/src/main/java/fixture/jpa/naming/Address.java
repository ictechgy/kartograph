package fixture.jpa.naming;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;

/** 임베디드 값 타입이다 — 중첩 임베디드도 포함한다. */
@Embeddable
public class Address {
    String streetName;
    String zipCode;
    @Embedded
    GeoPoint geoPoint;
}
