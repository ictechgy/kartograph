package fixture.jpa.naming;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import java.util.List;
import java.util.Set;

/** @ElementCollection의 암묵·명시 컬렉션 테이블과 컬럼을 고정한다. */
@Entity
public class Article {
    @Id
    Long articleId;
    @ElementCollection
    Set<String> tagNames;
    @ElementCollection
    @CollectionTable(name = "ArticleAlias", joinColumns = @JoinColumn(name = "articleRef"))
    @Column(name = "aliasText")
    Set<String> aliases;
    @ElementCollection
    List<Address> pastAddresses;
    @ElementCollection
    @OrderColumn
    List<String> orderedNotes;
}
