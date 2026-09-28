package fixture.jpa.naming;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import java.util.List;

/** 연관의 대상 엔티티다 — mappedBy 쪽은 컬럼을 만들지 않는다. */
@Entity
public class Department {
    @Id
    Long deptId;
    String deptName;
    @OneToMany(mappedBy = "homeDepartment")
    List<Employee> members;
}
