package fixture.jpa.naming;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;

/** 암묵 테이블·컬럼 이름과 camelCase 분리 경계(숫자·연속 대문자)를 고정한다. */
@Entity
public class BasicNames {
    @Id
    Long id;
    String title;
    String createdAt;
    String URLValue;
    String address2Line;
    String line2Address;
    String x;
    String aB;
    String fooBar1;
    String already_snake;
    String HTMLParser;
    String version2;
    @Transient
    String ignoredField;
    transient String alsoIgnored;
    static String staticIgnored;
}
