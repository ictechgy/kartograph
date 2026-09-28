package fixture.jpa.naming;

import jakarta.persistence.Entity;

/** MappedSuperclass를 상속한 엔티티다. */
@Entity
public class Invoice extends AuditedBase {
    Long totalAmount;
}
