package fixture.jpa.naming;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import java.util.Set;

/** 양방향 @ManyToMany의 역방향(mappedBy) 쪽이다. */
@Entity
public class Project {
    @Id
    Long projectId;
    String labelText;
    @ManyToMany(mappedBy = "projects")
    Set<Employee> members;
}
