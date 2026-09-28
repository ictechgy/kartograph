package fixture.jpa.naming;

import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Table;

/** JOINED 상속과 명시 판별 컬럼을 고정한다. */
@Entity
@Table(name = "payments")
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(name = "paymentKind")
public class Payment {
    @Id
    Long paymentId;
    Long amountCents;
}
