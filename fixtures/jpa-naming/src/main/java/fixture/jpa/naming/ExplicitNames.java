package fixture.jpa.naming;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 명시 이름에도 physical 전략이 적용되는지, schema 한정이 어떻게 나오는지 고정한다. */
@Entity
@Table(name = "OrderLine", schema = "SalesData")
public class ExplicitNames {
    @Id
    @Column(name = "lineId")
    Long id;
    @Column(name = "display_name")
    String displayName;
    @Column(name = "UnitPrice", nullable = false)
    Long unitPrice;
    @Column(length = 40)
    String itemCode;
}
