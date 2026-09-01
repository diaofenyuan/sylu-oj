package oj.practice;

import oj.shared.ApiException;
import oj.shared.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地自测执行器（仅开发/内测）：把学生代码编译后以样例或自定义输入试跑。
 * 非沙盒实现，生产环境（oj.judge.local-run.enabled=false）整体禁用；
 * 生产判题一律走 Judge Gateway Agent 隔离沙盒。
 * <p>ACM 精度：墙钟时间以纳秒计取到微秒；峰值内存来自 GNU time -v 的
 * wait4 rusage（Maximum resident set size），无 /usr/bin/time 时内存回退为 -1。</p>
 */
@Service
public class LocalCodeRunner {

    public record RunOutcome(String output, String compileError, String stderr,
                             int exitCode, long timeUs, long peakMemoryKb, boolean timedOut) {
    }

    private static final int COMPILE_TIMEOUT_MS = 30_000;
    private static final int MAX_OUTPUT_BYTES = 262_144;
    private static final Path GNU_TIME = Path.of("/usr/bin/time");

    @Value("${oj.judge.local-run.run-timeout-ms:8000}")
    private long runTimeoutMs;

    public RunOutcome run(String language, String code, String input) {
        RunOutcome outcome = runRaw(language, code, input);
        // GNU time 的 Maximum resident set size 含运行时底座（glibc/JVM/解释器），
        // 扣除各语言的空程序基线，得到近似纯逻辑内存。
        if (outcome.peakMemoryKb() < 0) {
            return outcome;
        }
        long baseline = baselineKb(language);
        if (baseline > 0) {
            long memoryKb = Math.max(0, outcome.peakMemoryKb() - baseline);
            return new RunOutcome(outcome.output(), outcome.compileError(), outcome.stderr(),
                    outcome.exitCode(), outcome.timeUs(), memoryKb, outcome.timedOut());
        }
        return outcome;
    }

    private RunOutcome runRaw(String language, String code, String input) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("oj-local-run-");
            // Windows 下 ProcessBuilder 不在子进程 directory 中解析相对可执行名，必须用绝对路径
            String exe = dir.resolve("main.exe").toString();
            return switch (language) {
                case "C" -> runCompiled(dir, "main.c", code, List.of("gcc", "main.c", "-o", exe, "-O2", "-std=c11"), List.of(exe), input);
                case "CPP" -> runCompiled(dir, "main.cpp", code, List.of("g++", "main.cpp", "-o", exe, "-O2", "-std=c++17"), List.of(exe), input);
                case "JAVA" -> runCompiled(dir, "Main.java", code, List.of("javac", "Main.java"), List.of("java", "-cp", dir.toString(), "Main"), input);
                case "PYTHON" -> runOnly(dir, List.of("python", dir.resolve("main.py").toString()), code, "main.py", input);
                default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "不支持的语言：" + language);
            };
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "自测运行环境初始化失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "自测运行被中断");
        } finally {
            cleanup(dir);
        }
    }

    private RunOutcome runCompiled(Path dir, String sourceName, String code, List<String> compileCmd,
                                   List<String> runCmd, String input) throws IOException, InterruptedException {
        Path source = dir.resolve(sourceName);
        Files.writeString(source, code, StandardCharsets.UTF_8);
        Path compileErr = dir.resolve("compile-err.txt");
        Process compile = new ProcessBuilder(compileCmd)
                .directory(dir.toFile())
                .redirectError(ProcessBuilder.Redirect.to(compileErr.toFile()))
                .start();
        if (!compile.waitFor(COMPILE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            compile.destroyForcibly();
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "编译超时");
        }
        if (compile.exitValue() != 0) {
            return new RunOutcome("", readCapped(compileErr), "", compile.exitValue(), 0, -1, false);
        }
        return execute(dir, runCmd, input);
    }

    private RunOutcome runOnly(Path dir, List<String> runCmd, String code, String sourceName,
                               String input) throws IOException, InterruptedException {
        Files.writeString(dir.resolve(sourceName), code, StandardCharsets.UTF_8);
        return execute(dir, runCmd, input);
    }

    private RunOutcome execute(Path dir, List<String> runCmd, String input) throws IOException, InterruptedException {
        Path inFile = dir.resolve("stdin.txt");
        Path outFile = dir.resolve("stdout.txt");
        Path errFile = dir.resolve("stderr.txt");
        Path metricsFile = dir.resolve("time.txt");
        Files.writeString(inFile, input, StandardCharsets.UTF_8);
        // 有 GNU time 时以 -o 收集 wait4 rusage 峰值内存（只取其内存指标）；
        // 墙钟时间由 Java 纳秒时钟测量（微秒级，比 time 的 10ms 小数点更精确）。
        List<String> command = new ArrayList<>();
        if (Files.isExecutable(GNU_TIME)) {
            command.add(GNU_TIME.toString());
            command.add("-o");
            command.add(metricsFile.toString());
            command.add("-v");
            command.add("--");
        }
        command.addAll(runCmd);
        long start = System.nanoTime();
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(inFile.toFile()))
                .redirectOutput(ProcessBuilder.Redirect.to(outFile.toFile()))
                .redirectError(ProcessBuilder.Redirect.to(errFile.toFile()))
                .start();
        boolean finished = process.waitFor(runTimeoutMs, TimeUnit.MILLISECONDS);
        long timeUs = (System.nanoTime() - start) / 1_000;
        if (!finished) {
            process.destroyForcibly();
            return new RunOutcome(readCapped(outFile), "", readCapped(errFile), -1, timeUs, -1, true);
        }
        return new RunOutcome(readCapped(outFile), "", readCapped(errFile),
                process.exitValue(), timeUs, peakRssFrom(metricsFile), false);
    }

    /** 解析 GNU time -v 输出中的峰值驻留内存（KB）；不存在或格式意外时返回 -1。 */
    private static long peakRssFrom(Path metricsFile) {
        try {
            String text = Files.readString(metricsFile, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("Maximum resident set size \\(kbytes\\):\\s+(\\d+)\\b")
                    .matcher(text);
            return m.find() ? Long.parseLong(m.group(1)) : -1;
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    /** 各语言空程序的峰值 RSS 基线（glibc/JVM/解释器运行时底座），首次测量后缓存。 */
    private final Map<String, Long> baselinesKb = new ConcurrentHashMap<>();

    private long baselineKb(String language) {
        return baselinesKb.computeIfAbsent(language, key -> {
            String emptyCode = switch (key) {
                case "C" -> "int main(void) { return 0; }";
                case "CPP" -> "int main() { return 0; }";
                case "PYTHON" -> "";
                case "JAVA" -> "public class Main { public static void main(String[] args) {} }";
                default -> null;
            };
            if (emptyCode == null) {
                return -1L;
            }
            RunOutcome r = runRaw(key, emptyCode, "");
            return r.peakMemoryKb() > 0 ? r.peakMemoryKb() : -1;
        });
    }

    private String readCapped(Path file) {
        try {
            if (!Files.exists(file)) {
                return "";
            }
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > MAX_OUTPUT_BYTES) {
                return new String(bytes, 0, MAX_OUTPUT_BYTES, StandardCharsets.UTF_8) + "\n…（输出超长截断）";
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private void cleanup(Path dir) {
        if (dir == null) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // 临时目录清理失败不影响结果
        }
    }
}
