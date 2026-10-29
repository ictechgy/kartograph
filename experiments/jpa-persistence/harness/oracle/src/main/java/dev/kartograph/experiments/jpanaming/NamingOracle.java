package dev.kartograph.experiments.jpanaming;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.hibernate.Version;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;

/**
 * 벡터 엔티티를 실제 Hibernate로 부트스트랩해 SchemaExport DDL을 만들고,
 * DDL에서 테이블→컬럼 이름을 읽어 JSON으로 쓴다.
 *
 * 인자: {@code <profile> <output-dir>}. profile은 {@code spring-boot}(Boot 기본 전략)
 * 또는 {@code hibernate}(Hibernate 기본 전략)다. 이 결과가 kartograph 명명 벡터의
 * 실행 근거(provenance: execution)다 — DDL 원문도 함께 남겨 사람이 대조할 수 있게 한다.
 */
public final class NamingOracle {

    private static final String VECTOR_PACKAGE = "fixture/jpa/naming/";

    private NamingOracle() {
    }

    /** 오라클 진입점이다 — 실패는 예외로 그대로 드러내 조용한 부분 결과를 막는다. */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: NamingOracle <spring-boot|hibernate> <output-dir>");
        }
        String profile = args[0];
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        Map<String, String> strategies = strategiesFor(profile);
        Path ddl = output.resolve("schema.sql");
        Files.deleteIfExists(ddl);
        export(strategies, ddl);
        Map<String, TreeSet<String>> tables = parseDdl(Files.readString(ddl));
        Files.writeString(output.resolve("tables.json"), render(profile, strategies, tables));
    }

    /** profile별 hibernate 명명 설정이다 — Boot 기본값은 Boot 자동 구성 소스와 같은 클래스를 쓴다. */
    private static Map<String, String> strategiesFor(String profile) {
        Map<String, String> settings = new TreeMap<>();
        if (profile.equals("spring-boot")) {
            settings.put("hibernate.physical_naming_strategy", firstPresent(
                "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
            settings.put("hibernate.implicit_naming_strategy", firstPresent(
                "org.springframework.boot.hibernate.SpringImplicitNamingStrategy",
                "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy"));
        } else if (!profile.equals("hibernate")) {
            throw new IllegalArgumentException("unknown profile: " + profile);
        }
        return settings;
    }

    /** 후보 중 classpath에 있는 첫 클래스 이름이다. */
    private static String firstPresent(String... classNames) {
        for (String name : classNames) {
            try {
                Class.forName(name);
                return name;
            } catch (ClassNotFoundException missing) {
                // 다음 후보를 본다 — 모두 없으면 아래에서 원인과 함께 실패한다.
            }
        }
        throw new IllegalStateException("none of the naming strategy classes is on the classpath: " + String.join(", ", classNames));
    }

    /** DB 연결 없이 H2 방언으로 create DDL 스크립트만 쓴다 (SchemaExport와 같은 hbm2ddl 스크립트 경로). */
    private static void export(Map<String, String> strategies, Path ddl) throws Exception {
        StandardServiceRegistryBuilder builder = new StandardServiceRegistryBuilder()
            .applySetting("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
            .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false");
        strategies.forEach(builder::applySetting);
        StandardServiceRegistry registry = builder.build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            for (Class<?> type : vectorClasses()) {
                sources.addAnnotatedClass(type);
            }
            Metadata metadata = sources.buildMetadata();
            // hibernate-core의 표준 스키마 생성 경로(hbm2ddl 스크립트 출력)를 그대로 탄다.
            Map<String, Object> generation = new HashMap<>(registry.getService(
                org.hibernate.engine.config.spi.ConfigurationService.class).getSettings());
            generation.put("jakarta.persistence.schema-generation.scripts.action", "create");
            generation.put("jakarta.persistence.schema-generation.scripts.create-target", ddl.toString());
            generation.put("hibernate.hbm2ddl.delimiter", ";");
            SchemaManagementToolCoordinator.process(metadata, registry, generation, action -> { });
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    /** 벡터 패키지의 JPA 어노테이션 클래스를 이름순으로 모은다. */
    private static List<Class<?>> vectorClasses() throws IOException, URISyntaxException, ClassNotFoundException {
        Path marker = Path.of(NamingOracle.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Path> roots = new ArrayList<>();
        // Java와 Kotlin 출력 디렉터리가 나뉘므로 형제 classes 디렉터리를 모두 본다.
        try (Stream<Path> siblings = Files.list(marker.getParent().getParent())) {
            siblings.map(language -> language.resolve("main")).filter(Files::isDirectory).forEach(roots::add);
        }
        TreeSet<String> names = new TreeSet<>();
        for (Path root : roots) {
            Path vectors = root.resolve(VECTOR_PACKAGE);
            if (!Files.isDirectory(vectors)) continue;
            try (Stream<Path> files = Files.list(vectors)) {
                files.map(path -> root.relativize(path).toString())
                    .filter(name -> name.endsWith(".class") && !name.contains("$"))
                    .forEach(name -> names.add(name.substring(0, name.length() - 6).replace('/', '.')));
            }
        }
        List<Class<?>> classes = new ArrayList<>();
        for (String name : names) {
            Class<?> type = Class.forName(name);
            if (isJpaType(type)) classes.add(type);
        }
        if (classes.isEmpty()) throw new IllegalStateException("no vector entity classes were found under " + roots);
        return classes;
    }

    private static boolean isJpaType(Class<?> type) {
        return type.isAnnotationPresent(jakarta.persistence.Entity.class)
            || type.isAnnotationPresent(jakarta.persistence.Embeddable.class)
            || type.isAnnotationPresent(jakarta.persistence.MappedSuperclass.class);
    }

    /** `create table` 문에서 테이블 이름과 컬럼 이름만 읽는다 — 인용 부호는 벗긴다. */
    static Map<String, TreeSet<String>> parseDdl(String ddl) {
        Map<String, TreeSet<String>> tables = new TreeMap<>();
        for (String raw : ddl.split(";")) {
            String statement = raw.strip();
            if (!statement.regionMatches(true, 0, "create table ", 0, 13)) continue;
            int open = statement.indexOf('(');
            String table = unquoteQualified(statement.substring(13, open).strip());
            TreeSet<String> columns = new TreeSet<>();
            for (String item : topLevelItems(statement.substring(open + 1, statement.lastIndexOf(')')))) {
                String lower = item.toLowerCase();
                if (lower.startsWith("primary key") || lower.startsWith("unique") || lower.startsWith("constraint")
                    || lower.startsWith("check") || lower.startsWith("foreign key")) continue;
                columns.add(unquote(firstToken(item)));
            }
            tables.put(table, columns);
        }
        return tables;
    }

    private static List<String> topLevelItems(String body) {
        List<String> items = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int index = 0; index < body.length(); index++) {
            char c = body.charAt(index);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                items.add(body.substring(start, index).strip());
                start = index + 1;
            }
        }
        items.add(body.substring(start).strip());
        return items;
    }

    private static String firstToken(String item) {
        if (item.startsWith("\"") || item.startsWith("`")) {
            int close = item.indexOf(item.charAt(0), 1);
            return item.substring(0, close + 1);
        }
        int space = item.indexOf(' ');
        return space < 0 ? item : item.substring(0, space);
    }

    private static String unquoteQualified(String name) {
        List<String> parts = new ArrayList<>();
        for (String part : name.split("\\.")) parts.add(unquote(part));
        return String.join(".", parts);
    }

    private static String unquote(String name) {
        if (name.length() >= 2 && (name.startsWith("\"") && name.endsWith("\"") || name.startsWith("`") && name.endsWith("`"))) {
            return name.substring(1, name.length() - 1);
        }
        return name;
    }

    /** 결정적 JSON이다 — 키와 컬럼 모두 정렬한다. */
    private static String render(String profile, Map<String, String> strategies, Map<String, TreeSet<String>> tables) {
        StringBuilder out = new StringBuilder();
        out.append("{\n  \"hibernateVersion\": \"").append(Version.getVersionString()).append("\",\n");
        out.append("  \"profile\": \"").append(profile).append("\",\n");
        out.append("  \"strategies\": {");
        List<String> entries = new ArrayList<>();
        strategies.forEach((key, value) -> entries.add("\"" + key + "\": \"" + value + "\""));
        out.append(String.join(", ", entries)).append("},\n  \"tables\": {\n");
        List<String> rows = new ArrayList<>();
        tables.forEach((table, columns) -> {
            List<String> quoted = new ArrayList<>();
            columns.forEach(column -> quoted.add("\"" + column + "\""));
            rows.add("    \"" + table + "\": [" + String.join(", ", quoted) + "]");
        });
        out.append(String.join(",\n", rows)).append("\n  }\n}\n");
        return out.toString();
    }
}
