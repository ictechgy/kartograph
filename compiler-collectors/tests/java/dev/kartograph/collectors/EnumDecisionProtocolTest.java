package dev.kartograph.collectors;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** 컴파일러 모형 없이 프로토콜과 생산자 제한 불변식을 검증한다. tests/run.py에서 실행한다. */
public final class EnumDecisionProtocolTest {
    private static final String HASH = "1".repeat(64);
    private static final EvidenceProtocol.Source SOURCE = new EvidenceProtocol.Source("src/Enums.kt", HASH);
    private static final String ENUM = "class:fixture/Phase";
    private static final String NEW = "field:fixture/Phase#NEW:Lfixture/Phase;";

    public static void main(String[] arguments) throws Exception {
        Path root = Path.of(arguments[0]);
        Files.createDirectories(root);
        Path token = root.resolve("token");
        Files.writeString(token, HASH);
        Path output = root.resolve("evidence.tsv");
        EvidenceProtocol.Options options = new EvidenceProtocol.Options(root, output, token, "kotlin-constants", false, true);

        EvidenceProtocol.EnumDecision empty = decision(0, List.of());
        EvidenceProtocol.EnumDecision finite = decision(10, List.of(NEW));
        EvidenceProtocol.EnumDecisionBuffer buffer = new EvidenceProtocol.EnumDecisionBuffer();
        buffer.observe(); buffer.add(finite);
        buffer.observe(); buffer.unsupported();
        buffer.observe(); buffer.ignored();
        check(buffer.observedCount() == 3 && buffer.decisionsList().size() == 1 &&
            buffer.unsupportedCount() == 1 && buffer.ignoredCount() == 1, "outcome accounting");
        write(options, buffer.decisionsList(), 3, 1, 1);
        String text = Files.readString(output);
        check(text.startsWith("format\tkartograph-compiler-evidence\t4\n"), "v4 header");
        check(text.contains("enumStats\t3\t1\t1\t1\n"), "v4 stats");
        check(text.contains("enumDecision\t" + encoded(SOURCE.path()) + "\t" + HASH + "\t10\t11\t1\t11\t-\t" +
            encoded(ENUM) + "\tFINITE_MEMBERSHIP\ttrue\t1\t" + encoded(NEW) + "\n"), "exact encoded source row");

        EvidenceProtocol.EnumDecision nested = new EvidenceProtocol.EnumDecision(SOURCE, 20, 30, 2, 1,
            "class:fixture/Host$Nested", "class:fixture/Host$Phase", "EXPLICIT_FALLBACK", false,
            List.of("field:fixture/Host$Phase#A:Lfixture/Host$Phase;"));
        write(options, List.of(nested, empty, finite), 3, 0, 0);
        List<String> rows = Files.readAllLines(output).stream().filter(row -> row.startsWith("enumDecision\t")).toList();
        check(rows.get(0).split("\t", -1).length == 12, "empty finite set");
        check(rows.get(1).split("\t", -1)[3].equals("10"), "deterministic source range order");

        rejected(() -> decision(0, List.of(NEW, NEW)));
        rejected(() -> decision(0, List.of(NEW, "field:fixture/Phase#A:Lfixture/Phase;")));
        rejected(() -> decision(0, List.of("field:unrelated/Phase#NEW:Lunrelated/Phase;")));
        rejected(() -> new EvidenceProtocol.EnumDecision(SOURCE, -1, 2, 1, 1, null, ENUM, "FINITE_MEMBERSHIP", false, List.of()));
        rejected(() -> new EvidenceProtocol.EnumDecision(SOURCE, 1, 1, 1, 1, null, ENUM, "FINITE_MEMBERSHIP", false, List.of()));
        rejected(() -> new EvidenceProtocol.EnumDecision(SOURCE, 0, 1, 0, 1, null, ENUM, "FINITE_MEMBERSHIP", false, List.of()));
        rejected(() -> new EvidenceProtocol.EnumDecision(SOURCE, 0, 1, 1, 1, null, ENUM, "INVOCATION_SELECTION", false, List.of()));
        ArrayList<String> constants = new ArrayList<>();
        for (int index = 0; index < EvidenceProtocol.MAX_ENUM_CONSTANTS; index++) {
            constants.add(String.format("field:fixture/Phase#C%04d:Lfixture/Phase;", index));
        }
        check(decision(0, constants).constants().size() == 4096, "constant bound accepted");
        constants.add("field:fixture/Phase#ZZZZ:Lfixture/Phase;");
        rejected(() -> decision(0, constants));

        rejected(() -> write(options, List.of(finite), 2, 0, 0));
        rejected(() -> write(options, List.of(), EvidenceProtocol.MAX_ROWS + 1, 0, EvidenceProtocol.MAX_ROWS + 1));
        rejected(() -> write(options, List.of(finite, finite), 2, 0, 0));
        EvidenceProtocol.EnumDecision foreignSource = new EvidenceProtocol.EnumDecision(
            new EvidenceProtocol.Source(SOURCE.path(), "2".repeat(64)), 0, 1, 1, 1, null, ENUM, "FINITE_MEMBERSHIP", false, List.of());
        rejected(() -> write(options, List.of(foreignSource), 1, 0, 0));
        rejected(() -> EvidenceProtocol.write(options, "2.4.10", HASH, List.of(SOURCE), 0, List.of()));
        rejected(() -> new EvidenceProtocol.Options(root, output, token, "dagger-bindings", false, true));
        rejected(() -> EvidenceProtocol.parse("kotlin-constants", "root=" + root, "output=" + output, "token=" + token, "enumDecisions=TRUE"));
        check(EvidenceProtocol.parse("javac-constants", "root=" + root, "output=" + output, "token=" + token,
            "enumDecisions=true").enumDecisions(), "enum option parsed");
        rejected(() -> EvidenceProtocol.parse("javac-constants", "root=" + root, "output=" + output, "token=" + token,
            "enumDecisions=true", "enumDecisions=true"));
        rejected(() -> EvidenceProtocol.parse("javac-constants", "root=" + root, "output=" + output, "token=" + token,
            "enumDecisions=true", "enumDecisions=false"));
        rejected(() -> EvidenceProtocol.parse("javac-constants", "root=" + root, "output=" + output, "token=" + token,
            "collector=kotlin-constants", "enumDecisions=true"));
        check(options.withCollector("javac-constants").enumDecisions(), "opt-in survives collector change");

        EvidenceProtocol.EnumDecisionBuffer missing = new EvidenceProtocol.EnumDecisionBuffer();
        missing.observe();
        rejected(missing::decisionsList);
        rejected(missing::observe);
        EvidenceProtocol.EnumDecisionBuffer extra = new EvidenceProtocol.EnumDecisionBuffer();
        rejected(extra::ignored);
        rejected(extra::observe);
        EvidenceProtocol.EnumDecisionBuffer duplicate = new EvidenceProtocol.EnumDecisionBuffer();
        duplicate.observe(); duplicate.add(finite); duplicate.observe();
        rejected(() -> duplicate.add(finite));
        rejected(duplicate::decisionsList);
        EvidenceProtocol.EnumDecisionBuffer limits = new EvidenceProtocol.EnumDecisionBuffer();
        int accepted = 0;
        try {
            for (; accepted <= EvidenceProtocol.MAX_ROWS; accepted++) {
                limits.observe();
                limits.add(decision(accepted * 2, List.of(NEW)));
            }
            throw new AssertionError("unbounded enum decision buffer");
        } catch (EvidenceProtocol.EnumDecisionLimitException expected) {
            check(accepted > 0 && accepted <= EvidenceProtocol.MAX_ROWS, "bounded accumulated rows/bytes");
        }
        rejected(limits::decisionsList);
        rejected(limits::observe);
        EvidenceProtocol.EnumDecisionBuffer observations = new EvidenceProtocol.EnumDecisionBuffer();
        for (int index = 0; index < EvidenceProtocol.MAX_ROWS; index++) { observations.observe(); observations.ignored(); }
        check(observations.observedCount() == EvidenceProtocol.MAX_ROWS, "observation boundary accepted");
        rejected(observations::observe);
        rejected(observations::decisionsList);

        // 명시적 비활성화와 v4 지원 writer 모두 v1/v2/v3 직렬화 바이트를 그대로 유지한다.
        for (boolean calls : List.of(false, true)) {
            EvidenceProtocol.Options legacy = new EvidenceProtocol.Options(root, output, token, "kotlin-constants", calls);
            List<EvidenceProtocol.CallPosition> positions = calls ? List.of(new EvidenceProtocol.CallPosition(
                "method:fixture/A#a()V", "method:fixture/A#b()V", SOURCE, 0, 1, 1, 1)) : List.of();
            EvidenceProtocol.write(legacy, "2.4.10", HASH, List.of(SOURCE), 0, List.of(), null, positions, positions.size(), 0, 0);
            byte[] old = Files.readAllBytes(output);
            String expectedLegacy = "format\tkartograph-compiler-evidence\t" + (calls ? "3" : "1") + "\n" +
                "collector\tkotlin-constants\ncompiler\t2.4.10\ntoken\t" + HASH + "\nartifact\t" + HASH +
                "\nsource\t" + encoded(SOURCE.path()) + "\t" + HASH + "\nunmapped\t0\n";
            if (calls) expectedLegacy += "callStats\t1\t1\t0\t0\ncall\t" + encoded("method:fixture/A#a()V") +
                "\t" + encoded("method:fixture/A#b()V") + "\t" + encoded(SOURCE.path()) + "\t" + HASH + "\t0\t1\t1\t1\n";
            check(new String(old, StandardCharsets.UTF_8).equals(expectedLegacy), "legacy golden bytes");
            EvidenceProtocol.write(legacy, "2.4.10", HASH, List.of(SOURCE), 0, List.of(), null, positions, positions.size(), 0, 0,
                List.of(), 0, 0, 0);
            check(java.util.Arrays.equals(old, Files.readAllBytes(output)), "legacy writer bytes");
            check(Files.readString(output).startsWith("format\tkartograph-compiler-evidence\t" + (calls ? "3" : "1") + "\n"),
                "legacy version");
            rejected(() -> write(legacy, List.of(finite), 1, 0, 0));
        }
        EvidenceProtocol.Options generated = new EvidenceProtocol.Options(root, output, token, "javac-processors");
        EvidenceProtocol.Generation generation = new EvidenceProtocol.Generation("fixture.Processor", HASH, List.of(SOURCE));
        EvidenceProtocol.write(generated, "17.0", HASH, List.of(SOURCE), 0, List.of(), generation);
        byte[] oldGenerated = Files.readAllBytes(output);
        String expectedGenerated = "format\tkartograph-compiler-evidence\t2\ncollector\tjavac-processors\ncompiler\t17.0\ntoken\t" +
            HASH + "\nartifact\t" + HASH + "\nprocessor\t" + encoded("fixture.Processor") + "\nprocessorArtifact\t" + HASH +
            "\ngenerated\t" + encoded(SOURCE.path()) + "\t" + HASH + "\nsource\t" + encoded(SOURCE.path()) + "\t" + HASH + "\nunmapped\t0\n";
        check(new String(oldGenerated, StandardCharsets.UTF_8).equals(expectedGenerated), "v2 golden bytes");
        EvidenceProtocol.write(generated, "17.0", HASH, List.of(SOURCE), 0, List.of(), generation,
            List.of(), 0, 0, 0, List.of(), 0, 0, 0);
        check(java.util.Arrays.equals(oldGenerated, Files.readAllBytes(output)), "v2 opt-out bytes");

        EvidenceProtocol.Source unicodeSource = new EvidenceProtocol.Source("src/분기.kt", HASH);
        EvidenceProtocol.EnumDecision unicode = new EvidenceProtocol.EnumDecision(unicodeSource, 0, 1, 1, 1,
            "class:fixture/호스트", "class:fixture/상태", "FINITE_MEMBERSHIP", false,
            List.of("field:fixture/상태#새로운:Lfixture/상태;"));
        EvidenceProtocol.write(options, "2.4.10", HASH, List.of(unicodeSource), 0, List.of(), null,
            List.of(), 0, 0, 0, List.of(unicode), 1, 0, 0);
        check(Files.readString(output).contains(encoded("field:fixture/상태#새로운:Lfixture/상태;")), "UTF-8 encoded byte budget");

        EvidenceProtocol.Options combined = new EvidenceProtocol.Options(root, output, token, "kotlin-constants", true, true);
        EvidenceProtocol.write(combined, "2.4.10", HASH, List.of(SOURCE), 0, List.of(), null,
            List.of(new EvidenceProtocol.CallPosition("method:fixture/A#a()V", "method:fixture/A#b()V", SOURCE, 0, 1, 1, 1)), 1, 0, 0,
            List.of(finite), 1, 0, 0);
        text = Files.readString(output);
        check(text.contains("callStats\t1\t1\t0\t0\n") && text.contains("enumStats\t1\t1\t0\t0\n"), "independent opt-ins coexist");
    }

    private static EvidenceProtocol.EnumDecision decision(int offset, List<String> constants) {
        return new EvidenceProtocol.EnumDecision(SOURCE, offset, offset + 1, 1, offset + 1, null, ENUM,
            "FINITE_MEMBERSHIP", true, constants);
    }

    private static void write(EvidenceProtocol.Options options, List<EvidenceProtocol.EnumDecision> decisions,
        int observed, int unsupported, int ignored) {
        EvidenceProtocol.write(options, "2.4.10", HASH, List.of(SOURCE), 0, List.of(), null,
            List.of(), 0, 0, 0, decisions, observed, unsupported, ignored);
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void rejected(Runnable operation) {
        try { operation.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid enum decision input accepted");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
