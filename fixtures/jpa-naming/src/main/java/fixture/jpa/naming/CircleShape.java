package fixture.jpa.naming;

import jakarta.persistence.Entity;

/** TABLE_PER_CLASS 구체 엔티티다. */
@Entity
public class CircleShape extends Shape {
    Double radiusValue;
}
