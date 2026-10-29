package fixture.jpa.naming;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;

/** TABLE_PER_CLASS 상속 — 구체 하위 엔티티마다 전체 컬럼을 가진 테이블이다. */
@Entity
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
public abstract class Shape {
    @Id
    Long id;
    String shapeColor;
}
