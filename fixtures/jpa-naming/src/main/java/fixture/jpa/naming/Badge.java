package fixture.jpa.naming;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** 연관 대상 엔티티다. */
@Entity
public class Badge {
    @Id
    Long badgeId;
    String labelText;
}
