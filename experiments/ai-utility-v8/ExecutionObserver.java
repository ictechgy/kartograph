package evaluation;

import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 실제 JUnit 실행과 원본 method identity의 대응을 기록하는 실험 전용 관찰자다. */
public final class ExecutionObserver implements TestExecutionListener {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private final Map<String, TestIdentifier> identifiers = new HashMap<>();
    private final Set<String> discovered = new HashSet<>();
    private final Map<String, Row> terminal = new TreeMap<>();
    private BufferedWriter writer;
    private Path journal;
    private boolean healthy;
    private int containerFailures;
    private int skippedContainers;

    private record Row(String uid, String method, String status, String errorClass, boolean derivedSkip) {}

    private static String quote(String text) {
        if (text == null) return "null";
        StringBuilder value = new StringBuilder("\"");
        for (char ch : text.toCharArray()) {
            if (ch == '\\' || ch == '"') value.append('\\').append(ch);
            else if (ch < 32) value.append(String.format("\\u%04x", (int) ch));
            else value.append(ch);
        }
        return value.append('"').toString();
    }

    private void emit(String value) {
        try {
            if (writer == null) throw new IOException("observer writer unavailable");
            writer.write(value); writer.newLine(); writer.flush();
        } catch (IOException failure) {
            healthy = false;
            System.err.println("Evaluation observer evidence write failed (" + failure.getClass().getSimpleName() + ")");
        }
    }

    private void register(TestIdentifier id) {
        identifiers.put(id.getUniqueId(), id);
        if (id.isTest()) discovered.add(id.getUniqueId());
    }

    @Override public synchronized void testPlanExecutionStarted(TestPlan plan) {
        identifiers.clear(); discovered.clear(); terminal.clear();
        healthy = true; containerFailures = 0; skippedContainers = 0;
        for (TestIdentifier root : plan.getRoots()) {
            register(root);
            for (TestIdentifier child : plan.getDescendants(root)) register(child);
        }
        try {
            String directory = System.getProperty("evaluation.observer.output");
            if (directory == null) throw new IOException("observer output is required");
            journal = Path.of(directory).resolve("plan-" + ProcessHandle.current().pid() + "-" + SEQUENCE.incrementAndGet() + ".jsonl");
            writer = Files.newBufferedWriter(journal, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            emit("{\"type\":\"start\",\"version\":1,\"initialTests\":" + discovered.size() + "}");
        } catch (IOException failure) {
            healthy = false; writer = null;
            System.err.println("Evaluation observer could not start evidence (" + failure.getClass().getSimpleName() + ")");
        }
    }

    @Override public synchronized void dynamicTestRegistered(TestIdentifier id) { register(id); }
    @Override public synchronized void executionStarted(TestIdentifier id) { register(id); }

    private String methodIdentity(TestIdentifier id) {
        Set<String> visited = new HashSet<>();
        while (id != null && visited.add(id.getUniqueId())) {
            if (id.getSource().orElse(null) instanceof MethodSource source) {
                Method method = source.getJavaMethod();
                StringBuilder descriptor = new StringBuilder("(");
                for (Class<?> type : method.getParameterTypes()) descriptor.append(type.descriptorString());
                descriptor.append(')').append(method.getReturnType().descriptorString());
                return "method:" + method.getDeclaringClass().getName().replace('.', '/') + "#" + method.getName() + descriptor;
            }
            id = identifiers.get(id.getParentId().orElse(""));
        }
        healthy = false;
        return null;
    }

    private void finish(TestIdentifier id, String status, String errorClass, boolean derived) {
        register(id);
        String identity = null;
        try { identity = methodIdentity(id); }
        catch (RuntimeException failure) { healthy = false; errorClass = failure.getClass().getName(); }
        Row previous = terminal.get(id.getUniqueId());
        if (previous != null && !(previous.derivedSkip() && previous.status().equals("skipped") && status.equals("skipped"))) {
            healthy = false;
            return;
        }
        terminal.put(id.getUniqueId(), new Row(id.getUniqueId(), identity, status, errorClass, derived));
    }

    @Override public synchronized void executionSkipped(TestIdentifier id, String reason) {
        register(id);
        if (id.isTest()) finish(id, "skipped", null, false);
        else {
            skippedContainers++;
            // container skip으로 callback이 생략된 기존 leaf도 별도 skip으로 보존한다.
            String prefix = id.getUniqueId() + "/";
            for (TestIdentifier child : Set.copyOf(identifiers.values())) {
                if (child.isTest() && child.getUniqueId().startsWith(prefix)) finish(child, "skipped", null, true);
            }
        }
    }

    @Override public synchronized void executionFinished(TestIdentifier id, TestExecutionResult result) {
        register(id);
        if (!id.isTest()) {
            if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) containerFailures++;
            return;
        }
        Throwable failure = result.getThrowable().orElse(null);
        String status = switch (result.getStatus()) {
            case SUCCESSFUL -> "pass";
            case ABORTED -> "aborted";
            case FAILED -> failure instanceof AssertionError ? "assertion-failure" : "error";
        };
        finish(id, status, failure == null ? null : failure.getClass().getName(), false);
    }

    @Override public synchronized void testPlanExecutionFinished(TestPlan plan) {
        for (Row row : terminal.values()) {
            emit("{\"type\":\"test\",\"uid\":" + quote(row.uid()) + ",\"method\":" + quote(row.method())
                + ",\"status\":" + quote(row.status()) + ",\"throwableClass\":" + quote(row.errorClass()) + "}");
        }
        boolean complete = healthy && containerFailures == 0 && discovered.equals(terminal.keySet());
        emit("{\"type\":\"complete\",\"complete\":" + complete + ",\"registeredTests\":" + discovered.size()
            + ",\"terminalTests\":" + terminal.size() + ",\"containerFailures\":" + containerFailures
            + ",\"skippedContainers\":" + skippedContainers + "}");
        if (writer != null) {
            try {
                writer.close();
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(journal)));
                String seal = "{\"journal\":" + quote(journal.getFileName().toString()) + ",\"sha256\":" + quote(hash)
                    + ",\"complete\":" + (complete && healthy) + "}";
                Files.writeString(journal.resolveSibling(journal.getFileName() + ".complete.json"), seal,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } catch (IOException | NoSuchAlgorithmException failure) {
                healthy = false;
                System.err.println("Evaluation observer evidence sealing failed (" + failure.getClass().getSimpleName() + ")");
            }
            writer = null;
        }
    }
}
