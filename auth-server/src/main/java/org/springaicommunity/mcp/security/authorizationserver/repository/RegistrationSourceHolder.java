package org.springaicommunity.mcp.security.authorizationserver.repository;

/**
 * 线程本地注册来源标记。
 * <p>
 * 由客户端注册的调用方在 {@code RegisteredClientRepository.save()} 之前设置来源，
 * {@link MybatisRegisteredClientRepository} 在持久化时读取并写入 {@code registration_source} 审计列，
 * 用于区分 DCR 动态注册 / PRE-REGISTERED 管理后台预注册 / ADMIN 管理后台创建。
 * <p>
 * 用 ThreadLocal 而非"save 即 DCR"启发式，是因为 {@code save()} 同时被 DCR provider
 * 和 {@code ClientRegistrationAdminController} 调用，必须在调用方显式声明来源。
 */
public final class RegistrationSourceHolder {

    private static final ThreadLocal<String> SOURCE = new ThreadLocal<>();

    private RegistrationSourceHolder() {}

    public static void set(String source) {
        SOURCE.set(source);
    }

    /** @return 当前线程的注册来源，未设置时返回 {@code null}（写入 DB 即 NULL） */
    public static String get() {
        return SOURCE.get();
    }

    public static void clear() {
        SOURCE.remove();
    }
}
