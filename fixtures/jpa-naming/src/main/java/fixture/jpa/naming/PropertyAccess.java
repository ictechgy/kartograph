package fixture.jpa.naming;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;

/** @Id가 getter에 있으면 property access다 — 컬럼은 getter 프로퍼티 이름에서 온다. */
@Entity
public class PropertyAccess {
    private Long key;
    private String internalName;
    private boolean activeFlag;

    @Id
    public Long getKey() { return key; }
    public void setKey(Long key) { this.key = key; }

    @Column(name = "publicName")
    public String getInternalName() { return internalName; }
    public void setInternalName(String internalName) { this.internalName = internalName; }

    public boolean isActiveFlag() { return activeFlag; }
    public void setActiveFlag(boolean activeFlag) { this.activeFlag = activeFlag; }

    @Transient
    public String getComputedLabel() { return internalName + "!"; }
}
