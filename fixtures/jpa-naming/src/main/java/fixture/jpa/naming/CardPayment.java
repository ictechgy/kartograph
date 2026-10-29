package fixture.jpa.naming;

import jakarta.persistence.Entity;

/** JOINED 하위 엔티티 — 부모 PK 이름의 join column을 가진 자기 테이블을 만든다. */
@Entity
public class CardPayment extends Payment {
    String cardLast4;
}
