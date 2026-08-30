package oj.practice;

import oj.shared.ApiException;
import oj.shared.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 本地自测执行器（仅开发/内测）：把学生代码编译后以样例或自定义输入试跑。
 * 非沙盒实现，生产环境（oj.judge.local-run.enabled=false）整体禁用；
 * 生产判题一律走 Judge Gateway Agent 隔离沙盒。
 */
@Service
public class LocalCodeRunner {

    public record RunOutcome(String output, String compileError, String stderr,
                             int exitCode, long timeMs, boolean timedOut) {
    }

    private static final int COMPILE_TIMEOUT_MS = 30_000;
    private static final int MAX_OUTPUT_BYTES = 262_144;

    @Value("${oj.judge.local-run.run-timeout-ms:8000}")
    private long runTimeoutMs;

    public RunOutcome run(String language, String code, String input) {
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
            return new RunOutcome("", readCapped(compileErr), "", compile.exitValue(), 0, false);
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
        Files.writeString(inFile, input, StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(runCmd)
                .directory(dir.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(inFile.toFile()))
                .redirectOutput(ProcessBuilder.Redirect.to(outFile.toFile()))
                .redirectError(ProcessBuilder.Redirect.to(errFile.toFile()))
                .start();
        long start = System.nanoTime();
        boolean finished = process.waitFor(runTimeoutMs, TimeUnit.MILLISECONDS);
        long timeMs = (System.nanoTime() - start) / 1_000_000;
        if (!finished) {
            process.destroyForcibly();
            return new RunOutcome(readCapped(outFile), "", readCapped(errFile), -1, timeMs, true);
        }
        return new RunOutcome(readCapped(outFile), "", readCapped(errFile), process.exitValue(), timeMs, false);
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
