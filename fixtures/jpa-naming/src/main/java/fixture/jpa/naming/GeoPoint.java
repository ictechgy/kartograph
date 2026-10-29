package fixture.jpa.naming;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** 중첩 임베디드 값 타입이다. */
@Embeddable
public class GeoPoint {
    Double latValue;
    @Column(name = "lng")
    Double lngValue;
}
