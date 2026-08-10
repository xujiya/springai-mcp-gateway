package org.springaicommunity.mcp.security.authorizationserver.config;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.server.authorization.settings.ConfigurationSettingNames;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

/**
 * JDBC-backed JWKSource — RSA signing key 持久化到 MySQL.
 * <p>
 * 启动时从 oauth2_jwk_key 表加载；若表为空则生成新的 2048-bit RSA key 并写入.
 * 这样 auth-server 重启后 JWK kid 不变，已签发的 JWT token 不失效.
 * <p>
 * 对标阿里云：生产环境 JWK key 持久化 + 定期轮换 (rotation).
 */
public class PersistentJWKSource implements JWKSource<SecurityContext> {

	private static final Logger logger = LoggerFactory.getLogger(PersistentJWKSource.class);

	private static final String SELECT_JWK = "SELECT jwk_json FROM oauth2_jwk_key WHERE expires_at IS NULL OR expires_at > NOW() ORDER BY created_at DESC";
	private static final String INSERT_JWK = "INSERT INTO oauth2_jwk_key (id, kid, jwk_json, created_at) VALUES (?, ?, ?, ?)";

	private final JdbcOperations jdbcOperations;
	private volatile JWKSet jwkSet;

	public PersistentJWKSource(JdbcOperations jdbcOperations) {
		this.jdbcOperations = jdbcOperations;
		this.jwkSet = loadOrGenerate();
	}

	@Override
	public List<JWK> get(JWKSelector jwkSelector, SecurityContext context) {
		return jwkSelector.select(jwkSet);
	}

	/**
	 * 从 DB 加载有效 JWK；没有则生成并持久化.
	 */
	private JWKSet loadOrGenerate() {
		try {
			// 尝试从 DB 加载
			List<String> rows = jdbcOperations.queryForList(SELECT_JWK, String.class);
			if (!rows.isEmpty()) {
				JWK loaded = JWK.parse(rows.get(0));
				logger.info("JWK key loaded from MySQL (kid={})", loaded.getKeyID());
				return new JWKSet(loaded);
			}

			// DB 为空 → 生成新 RSA key
			RSAKey rsaKey = generateRsaKey();
			persist(rsaKey);
			logger.info("JWK key generated and persisted to MySQL (kid={})", rsaKey.getKeyID());
			return new JWKSet(rsaKey);

		} catch (Exception e) {
			throw new IllegalStateException("Failed to load/generate JWK key", e);
		}
	}

	/**
	 * 生成 2048-bit RSA key with random kid.
	 */
	private RSAKey generateRsaKey() throws JOSEException {
		String kid = java.util.UUID.randomUUID().toString();
		return new RSAKeyGenerator(2048)
				.keyID(kid)
				.generate();
	}

	/**
	 * 持久化 JWK (含 private key) 到 MySQL.
	 */
	private void persist(RSAKey rsaKey) {
		String id = java.util.UUID.randomUUID().toString();
		String kid = rsaKey.getKeyID();
		String jwkJson = rsaKey.toJSONString();
		Timestamp now = Timestamp.from(Instant.now());

		jdbcOperations.update(INSERT_JWK, id, kid, jwkJson, now);
	}

	/**
	 * 强制重新加载 (用于 key rotation 场景).
	 */
	public void reload() {
		this.jwkSet = loadOrGenerate();
	}
}
