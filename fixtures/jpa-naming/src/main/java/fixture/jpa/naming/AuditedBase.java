package fixture.jpa.naming;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

/** @MappedSuperclass의 필드는 하위 엔티티 테이블의 컬럼이 된다. */
@MappedSuperclass
public abstract class AuditedBase {
    @Id
    Long id;
    String createdBy;
    @Version
    Long rowVersion;
}
