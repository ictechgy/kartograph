package fixture.jpa.naming;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import java.util.List;
import java.util.Set;

/** 연관의 암묵·명시 join column과 join table 이름을 고정한다. */
@Entity
public class Employee {
    @Id
    Long id;
    @ManyToOne
    Department homeDepartment;
    @ManyToOne
    @JoinColumn(name = "mentorRef")
    Employee mentor;
    @OneToOne
    ParkingSpot parkingSpot;
    @ManyToMany
    Set<Project> projects;
    @ManyToMany
    @JoinTable(
        name = "EmployeeSkill",
        joinColumns = @JoinColumn(name = "employeeRef"),
        inverseJoinColumns = @JoinColumn(name = "skillRef"))
    Set<Skill> skills;
    @OneToMany
    List<Badge> badges;
    @OneToMany
    @JoinColumn(name = "ownerEmployee")
    List<Laptop> laptops;
}
