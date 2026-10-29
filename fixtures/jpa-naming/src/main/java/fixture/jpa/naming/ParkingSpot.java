package fixture.jpa.naming;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;

/** @OneToOne의 역방향(mappedBy) 쪽이다 — 컬럼을 만들지 않는다. */
@Entity
public class ParkingSpot {
    @Id
    Long spotId;
    String labelText;
    @OneToOne(mappedBy = "parkingSpot")
    Employee holder;
}
