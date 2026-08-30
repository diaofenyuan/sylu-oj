package oj.judge;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Judge Gateway 协议契约校验（judge/protocol/judge-gateway.openapi.yaml）：
 * 端点齐备、不存在批量/全量测试数据接口、认证方式与固定状态码枚举符合设计。
 */
class JudgeGatewayProtocolTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadSpec() throws Exception {
        Path path = Paths.get("..", "..", "judge", "protocol", "judge-gateway.openapi.yaml")
                .toAbsolutePath().normalize();
        assertThat(path).exists();
        return new Yaml().load(Files.readString(path));
    }

    @Test
    @SuppressWarnings("unchecked")
    void contract_defines_required_endpoints_and_security() throws Exception {
        Map<String, Object> spec = loadSpec();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        assertThat(paths).containsKeys(
                "/tasks/claim",
                "/tasks/{taskUuid}/testcases/{order}",
                "/tasks/{taskUuid}/result");

        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Object> securitySchemes = (Map<String, Object>) components.get("securitySchemes");
        assertThat(securitySchemes).containsKeys("agentAuth", "mtlsAuth");
    }

    @Test
    @SuppressWarnings("unchecked")
    void protocol_has_no_batch_or_full_sync_testcase_endpoint() throws Exception {
        Map<String, Object> spec = loadSpec();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        for (String path : paths.keySet()) {
            assertThat(path.toLowerCase()).doesNotContain("batch", "bulk", "all", "sync", "export", "download");
            // 用例拉取必须绑定具体任务与具体用例序号
            if (path.contains("testcases")) {
                assertThat(path).isEqualTo("/tasks/{taskUuid}/testcases/{order}");
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void result_codes_are_fixed_enum_without_pending() throws Exception {
        Map<String, Object> spec = loadSpec();
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> result = (Map<String, Object>) schemas.get("JudgeResultSubmission");
        Map<String, Object> resultCode = (Map<String, Object>) result.get("properties");
        Map<String, Object> codeSchema = (Map<String, Object>) ((Map<String, Object>) resultCode.get("resultCode"));
        List<String> codes = (List<String>) codeSchema.get("enum");
        assertThat(codes).containsExactlyInAnyOrder("CE", "AC", "WA", "RE", "TLE", "MLE", "OLE", "PE", "SE", "BSC");
        assertThat(codes).doesNotContain("PD");
    }
}
