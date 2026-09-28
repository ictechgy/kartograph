package fixture.jpa.naming;

import jakarta.persistence.Entity;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;

/** JOINED 하위 엔티티의 명시 @PrimaryKeyJoinColumn이다. */
@Entity
@Table(name = "wire_payments")
@PrimaryKeyJoinColumn(name = "wirePaymentRef")
public class WirePayment extends Payment {
    String bankCode;
}
