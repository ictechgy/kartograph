package fixture.jpa.naming;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** @Embedded와 @AttributeOverride(s)를 고정한다. */
@Entity
public class Customer {
    @Id
    Long id;
    @Embedded
    Address homeAddress;
    @Embedded
    @AttributeOverrides({
        @AttributeOverride(name = "streetName", column = @Column(name = "billStreet")),
        @AttributeOverride(name = "zipCode", column = @Column(name = "billZip")),
        @AttributeOverride(name = "geoPoint.latValue", column = @Column(name = "billLat")),
        @AttributeOverride(name = "geoPoint.lngValue", column = @Column(name = "billLng"))
    })
    Address billingAddress;
}
