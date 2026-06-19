package com.fitness.admin.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.lang.NonNull;

/**
 * 启动期环境变量强校验,避免生产环境因缺关键配置而以默认值静默启动。
 *
 * <p>当关键密钥/口令使用占位符默认值时,直接抛出异常阻断启动。
 * 失败位置:{@link #onApplicationEvent(ApplicationEnvironmentPreparedEvent)}。
 *
 * <p>校验项:
 * <ul>
 *   <li>{@code MYSQL_PASSWORD} 必填且不能为默认 {@code root}</li>
 *   <li>{@code AI_API_KEY} 必填(非本地开发模式可放宽)</li>
 *   <li>{@code QINIU_SECRET_KEY} 必填</li>
 * </ul>
 */
@Slf4j
@Configuration
public class StartupEnvValidator implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final String[] FORBIDDEN_MYSQL_PASSWORDS = {"root", "", "password", "123456"};

    @Value("${spring.profiles.active:dev}")
    private String activeProfile;

    @Override
    public void onApplicationEvent(@NonNull ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment env = event.getEnvironment();
        String profile = env.getProperty("spring.profiles.active", "dev");

        validateMysqlPassword(env, profile);
        validateAiApiKey(env, profile);
        validateQiniuSecretKey(env, profile);
    }

    private void validateMysqlPassword(ConfigurableEnvironment env, String profile) {
        // 直接读原始环境变量,而非 spring.datasource.password。
        // 原因: Spring Cloud Bootstrap 上下文的 ApplicationEnvironmentPreparedEvent
        // 触发时 application.yml/application-prod.yml 尚未加载,spring.datasource.password
        // 属性不存在,getProperty 会返回默认值 "" 导致误判。
        // MYSQL_PASSWORD 等原始环境变量在 Bootstrap 和主上下文中都可见。
        String password = env.getProperty("MYSQL_PASSWORD", "");
        String username = env.getProperty("MYSQL_USER", "");

        for (String forbidden : FORBIDDEN_MYSQL_PASSWORDS) {
            if (forbidden.equals(password)) {
                throw new IllegalStateException(String.format(
                        "[启动校验] MYSQL_PASSWORD 使用了禁止值 \"%s\"(profile=%s, user=%s),生产环境严禁默认口令,请在 deploy/fitness-admin.env 中显式设置",
                        forbidden, profile, username));
            }
        }
    }

    private void validateAiApiKey(ConfigurableEnvironment env, String profile) {
        String apiKey = env.getProperty("AI_API_KEY", "");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            if ("prod".equals(profile) || "production".equals(profile)) {
                throw new IllegalStateException(
                        "[启动校验] 生产环境 AI_API_KEY 不能为空,会导致所有 AI 调用在运行期才报 401。请在 deploy/fitness-admin.env 中设置 AI_API_KEY");
            }
            log.warn("[启动校验] AI_API_KEY 为空(profile={}),AI 服务将无法调用", profile);
        }
    }

    private void validateQiniuSecretKey(ConfigurableEnvironment env, String profile) {
        String secretKey = env.getProperty("QINIU_SECRET_KEY", "");
        if (secretKey == null || secretKey.trim().isEmpty()) {
            if ("prod".equals(profile) || "production".equals(profile)) {
                throw new IllegalStateException(
                        "[启动校验] 生产环境 QINIU_SECRET_KEY 不能为空,文件上传将无法工作。请在 deploy/fitness-admin.env 中设置 QINIU_SECRET_KEY");
            }
            log.warn("[启动校验] QINIU_SECRET_KEY 为空(profile={}),文件上传功能不可用", profile);
        }
    }
}
