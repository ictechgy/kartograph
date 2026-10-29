package fixture.jpa.naming;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** 단방향 @OneToMany @JoinColumn의 대상이다 — FK 컬럼이 이 테이블에 생긴다. */
@Entity
public class NoteTag {
    @Id
    Long tagId;
    String tagText;
}
