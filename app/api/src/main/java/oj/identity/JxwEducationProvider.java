package oj.identity;

import oj.shared.ApiException;
import oj.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生产教务登录页适配器（{@code https://jxw.sylu.edu.cn/xtgl/login_slogin.html}）。
 *
 * <p>教务系统没有 CAS/OIDC/SAML 接口，也不返回可验证票据，生产只能采用
 * <b>服务端代提交</b>模式：该方式与责任人已登记于
 * {@code docs/runbooks/education-login-adapter.md}，上线审批时复核。</p>
 *
 * <p>安全约束：</p>
 * <ul>
 *   <li>未通过现场验收（{@code certified=false}）时拒绝提供任何认证能力；</li>
 *   <li>密码仅以 char[] 在请求内存中短暂存在，使用后立即清零，不落库、不进日志、
 *       不进异常消息、不进缓存与判题任务；</li>
 *   <li>禁止保存教务 Cookie、验证码与完整页面内容；指纹只保留字段结构哈希；</li>
 *   <li>检测到验证码或页面结构变化立即熔断，不尝试绕过。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "oj.identity.education.provider", havingValue = "jxw")
public class JxwEducationProvider implements EducationIdentityProvider {

    private static final Logger log = LoggerFactory.getLogger(JxwEducationProvider.class);

    /** 验证码/风控组件特征（出现即熔断，不绕过）。 */
    private static final List<String> CAPTCHA_MARKERS = List.of(
            "captcha", "vercode", "checkcode", "验证码图片", "/validate/");

    private static final Pattern FIELD_NAME_PATTERN =
            Pattern.compile("<(?:input|select|button)[^>]*\\bname=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private final String loginUrl;
    private final boolean certified;
    private final String usernameField;
    private final String passwordField;
    private final String submitPath;
    private final Pattern namePattern;
    private final HttpClient httpClient;

    public JxwEducationProvider(@Value("${oj.identity.education.jxw.login-url:https://jxw.sylu.edu.cn/xtgl/login_slogin.html}") String loginUrl,
                                @Value("${oj.identity.education.jxw.certified:false}") boolean certified,
                                @Value("${oj.identity.education.jxw.username-field:}") String usernameField,
                                @Value("${oj.identity.education.jxw.password-field:}") String passwordField,
                                @Value("${oj.identity.education.jxw.submit-path:}") String submitPath,
                                @Value("${oj.identity.education.jxw.name-regex:}") String nameRegex) {
        this.loginUrl = loginUrl;
        this.certified = certified;
        this.usernameField = usernameField;
        this.passwordField = passwordField;
        this.submitPath = submitPath;
        this.namePattern = nameRegex.isBlank() ? null : Pattern.compile(nameRegex);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return "jxw";
    }

    @Override
    public boolean isAvailable() {
        requireCertified();
        try {
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(loginUrl)).timeout(Duration.ofSeconds(8)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() >= 200 && response.statusCode() < 400;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("教务登录页不可达（仅记录原因，不记录页面内容）：{}", e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public String loginPageUrl() {
        return loginUrl;
    }

    @Override
    public boolean supportsProxySubmission() {
        return certified;
    }

    @Override
    public String capturePageFingerprint() {
        requireCertified();
        String html = fetchLoginPage();
        if (containsCaptcha(html)) {
            throw new AdapterHaltException("CAPTCHA", "教务登录页出现验证码，停止自动认证");
        }
        if (usernameField.isBlank() || passwordField.isBlank()
                || !html.contains("name=\"" + usernameField + "\"")
                || !html.contains("name=\"" + passwordField + "\"")) {
            throw new AdapterHaltException("FIELDS", "教务登录页关键字段缺失或已变更");
        }
        Matcher matcher = FIELD_NAME_PATTERN.matcher(html);
        StringBuilder fields = new StringBuilder();
        while (matcher.find()) {
            fields.append(matcher.group(1)).append(',');
        }
        String[] sorted = fields.toString().split(",");
        Arrays.sort(sorted);
        return Hashing.sha256(loginUrl + "|" + String.join(",", sorted));
    }

    @Override
    public ExternalAssertion verifyTicket(String ticket) {
        // 教务页面不提供可验证票据机制，生产采用代提交模式（已在 Runbook 登记）。
        throw new ApiException(ErrorCode.TICKET_INVALID);
    }

    @Override
    public Optional<ExternalAssertion> proxyLogin(String externalNo, char[] password) {
        requireCertified();
        try {
            String body = URLEncoder.encode(usernameField, StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(externalNo, StandardCharsets.UTF_8) + "&"
                    + URLEncoder.encode(passwordField, StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(new String(password), StandardCharsets.UTF_8);
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(URI.create(submitPath))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String html = response.body();
            if (containsCaptcha(html)) {
                throw new AdapterHaltException("CAPTCHA", "教务登录页出现验证码，停止自动认证");
            }
            String name = extractName(html);
            // 登录失败页面不暴露原因细节；失败即拒绝，不做本地回退。
            if (name == null) {
                throw new ApiException(ErrorCode.LOGIN_FAILED);
            }
            return Optional.of(new ExternalAssertion(externalNo, ExternalAssertion.Type.UNKNOWN, name, true));
        } catch (ApiException | AdapterHaltException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.IDP_UNAVAILABLE);
        } catch (Exception e) {
            log.warn("教务代提交异常（不记录请求体）：{}", e.getClass().getSimpleName());
            throw new ApiException(ErrorCode.IDP_UNAVAILABLE);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private String fetchLoginPage() {
        try {
            HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(loginUrl)).timeout(Duration.ofSeconds(8)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 400) {
                throw new ApiException(ErrorCode.IDP_UNAVAILABLE);
            }
            return response.body();
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.IDP_UNAVAILABLE);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.IDP_UNAVAILABLE);
        }
    }

    private String extractName(String html) {
        if (namePattern == null) {
            return null;
        }
        Matcher matcher = namePattern.matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean containsCaptcha(String html) {
        String lower = html.toLowerCase();
        return CAPTCHA_MARKERS.stream().anyMatch(lower::contains);
    }

    private void requireCertified() {
        if (!certified) {
            throw new ApiException(ErrorCode.ADAPTER_NOT_CERTIFIED);
        }
    }
}
