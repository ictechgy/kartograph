package fixture.jpa.naming;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** @Entity(name)만 있으면 JPA 엔티티 이름이 테이블 이름이 된다. */
@Entity(name = "WidgetItem")
public class NamedEntity {
    @Id
    Long id;
    String widgetLabel;
}
