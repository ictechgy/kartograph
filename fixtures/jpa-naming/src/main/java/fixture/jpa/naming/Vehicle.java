package fixture.jpa.naming;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** 기본 상속 전략(SINGLE_TABLE)과 기본 판별 컬럼 DTYPE을 고정한다. */
@Entity
public class Vehicle {
    @Id
    Long id;
    String plateNumber;
}
