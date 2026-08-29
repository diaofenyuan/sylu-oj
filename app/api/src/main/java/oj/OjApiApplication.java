package oj;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 在线判题系统 API：教学组织、题库、组卷与作业发布业务。
 *
 * <p>安全基线见 plan/single-server-security-design.md：
 * 对象级授权在每个服务层强制执行；统一异常不泄漏内部细节；
 * 审计事件记录全部状态变更。</p>
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class OjApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(OjApiApplication.class, args);
    }
}
