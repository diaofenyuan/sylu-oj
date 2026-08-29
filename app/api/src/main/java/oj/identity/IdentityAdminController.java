package oj.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import oj.shared.AccessGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员身份治理接口：身份变更告警确认、适配器熔断恢复、批量撤销会话。
 * 无堡垒机时管理来源限定为中转机/管理源（网络策略），操作全程审计。
 */
@RestController
@RequestMapping("/api/admin/identities")
public class IdentityAdminController {

    private final IdentityService identityService;
    private final AccessGuard accessGuard;

    public IdentityAdminController(IdentityService identityService, AccessGuard accessGuard) {
        this.identityService = identityService;
        this.accessGuard = accessGuard;
    }

    /** 待人工确认的身份告警与适配器状态。 */
    @GetMapping("/alerts")
    public Map<String, Object> alerts() {
        accessGuard.requireAdmin();
        Map<String, Object> body = new LinkedHashMap<>();
        List<ExternalIdentity> pending = identityService.pendingConfirmations();
        body.put("pendingIdentities", pending.stream()
                .map(i -> Map.of("id", i.getId(), "externalNo", i.getExternalNo(),
                        "status", i.getStatus().name(), "boundAt", i.getBoundAt().toString()))
                .toList());
        body.put("adapter", identityService.adapterStatus());
        return body;
    }

    public record ConfirmRequest(boolean accept) {
    }

    /** 学号复用/身份变更的人工确认：接受新指纹或停用身份并撤销会话。 */
    @PostMapping("/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable Long id, @Valid @RequestBody ConfirmRequest body) {
        accessGuard.requireAdmin();
        identityService.confirmIdentity(id, body.accept());
        return Map.of("ok", true);
    }

    @GetMapping("/adapter-status")
    public IdentityService.AdapterStatus adapterStatus() {
        accessGuard.requireAdmin();
        return identityService.adapterStatus();
    }

    /** 人工核查页面变化后恢复适配器，并以当前指纹作为新基线。 */
    @PostMapping("/adapter/resume")
    public Map<String, Object> resumeAdapter() {
        accessGuard.requireAdmin();
        identityService.resumeAdapter();
        return Map.of("ok", true);
    }

    public record RevokeRequest(@NotNull Long appUserId) {
    }

    @PostMapping("/revoke-sessions")
    public Map<String, Object> revokeSessions(@Valid @RequestBody RevokeRequest body) {
        accessGuard.requireAdmin();
        identityService.revokeAllSessions(body.appUserId());
        return Map.of("ok", true);
    }
}
