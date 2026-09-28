package fixture.jpa.naming;

import jakarta.persistence.Entity;

/** SINGLE_TABLE 하위 엔티티 — 컬럼이 루트 테이블에 들어간다. */
@Entity
public class Truck extends Vehicle {
    Integer axleCount;
}
