package fixture.jpa.naming;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import java.util.Set;

/** 명시 @Column 이름을 가진 PK를 참조하는 암묵 join column과 이름 없는 @JoinColumn을 고정한다. */
@Entity
public class OrderNote {
    @Id
    Long noteId;
    @ManyToOne
    ExplicitNames orderLine;
    @ManyToOne
    @JoinColumn
    NamedEntity widget;
    @OneToMany
    @JoinColumn
    Set<NoteTag> noteTags;
}
