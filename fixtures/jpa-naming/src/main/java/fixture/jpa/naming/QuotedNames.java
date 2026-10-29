package fixture.jpa.naming;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 인용된 식별자는 Hibernate 6과 7에서 physical 전략 처리가 다르다. */
@Entity
@Table(name = "\"UserAccount\"")
public class QuotedNames {
    @Id
    Long id;
    @Column(name = "`MixedCase`")
    String mixed;
    @Column(name = "\"loginName\"")
    String login;
}
