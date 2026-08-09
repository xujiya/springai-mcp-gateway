<template>
  <div class="security-view">
    <!-- Security Status Overview -->
    <div class="card">
      <h3>🛡️ 安全态势总览</h3>
      <div class="security-grid">
        <div v-for="item in securityItems" :key="item.label" :class="['sec-item', item.level]">
          <span class="sec-icon">{{ item.icon }}</span>
          <span class="sec-label">{{ item.label }}</span>
          <span :class="['badge', item.level === 'ok' ? 'green' : item.level === 'warn' ? 'orange' : 'red']">
            {{ item.value }}
          </span>
        </div>
      </div>
    </div>

    <!-- Auth Flow Diagram -->
    <div class="card">
      <h3>🔄 认证流程图</h3>
      <div class="flow-container">
        <div class="flow-row">
          <div class="flow-box client">
            <strong>客户端</strong>
            <small>pi / Cursor / CI/CD</small>
          </div>
          <div class="flow-arrow">
            <div class="arrow-line"></div>
            <span class="arrow-label">① 请求 + 凭证</span>
          </div>
          <div class="flow-box gateway">
            <strong>mcp-gateway</strong>
            <small>:8082 纯代理</small>
          </div>
          <div class="flow-arrow">
            <div class="arrow-line"></div>
            <span class="arrow-label">② 验证 + 转发</span>
          </div>
          <div class="flow-box backend">
            <strong>MCP 后端</strong>
            <small>:9092/:9093</small>
          </div>
        </div>

        <div class="flow-detail">
          <div class="flow-branch ak-branch">
            <h4>🔑 AK 模式</h4>
            <div class="flow-steps">
              <span class="step">X-API-Key: ak-id:sk-secret</span>
              <span class="step-arrow">→</span>
              <span class="step">ApiKeyAuthenticationFilter</span>
              <span class="step-arrow">→</span>
              <span class="step">bcrypt 验证 + scope 检查</span>
              <span class="step-arrow">→</span>
              <span class="step ok">✅ 转发</span>
            </div>
          </div>
          <div class="flow-branch oauth-branch">
            <h4>🔐 OAuth 模式</h4>
            <div class="flow-steps">
              <span class="step">Authorization: Bearer JWT</span>
              <span class="step-arrow">→</span>
              <span class="step">BearerTokenAuthenticationFilter</span>
              <span class="step-arrow">→</span>
              <span class="step">JWT 验签 + audience 检查</span>
              <span class="step-arrow">→</span>
              <span class="step ok">✅ 转发</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Security Checklist -->
    <div class="card">
      <h3>✅ 安全检查清单</h3>
      <table>
        <thead>
          <tr>
            <th>项目</th>
            <th>状态</th>
            <th>说明</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="check in checks" :key="check.item">
            <td><strong>{{ check.item }}</strong></td>
            <td>
              <span :class="['badge', check.ok ? 'green' : check.warn ? 'orange' : 'red']">
                {{ check.ok ? '✅ 通过' : check.warn ? '⚠️ 警告' : '❌ 失败' }}
              </span>
            </td>
            <td>{{ check.detail }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script>
import { ref, onMounted } from 'vue'
import { getAdminToken, listApiKeys, getAuthServerMetadata } from '../api/admin.js'

export default {
  name: 'SecurityView',
  setup() {
    const securityItems = ref([
      { icon: '🔑', label: 'AK 双部件模型', value: '已启用', level: 'ok' },
      { icon: '🔐', label: 'OAuth JWT', value: '已启用', level: 'ok' },
      { icon: '🚫', label: 'DCR 注册', value: '开放', level: 'warn' },
      { icon: '🔒', label: '暴力破解防护', value: '10次/5分钟', level: 'ok' },
      { icon: '⏱️', label: 'Token TTL', value: '24h access', level: 'ok' },
      { icon: '🍪', label: 'Cookie 安全', value: 'SameSite=Lax', level: 'ok' },
      { icon: '🌐', label: 'CORS', value: 'localhost 限制', level: 'ok' },
      { icon: '🖥️', label: 'nginx 版本隐藏', value: 'server_tokens off', level: 'ok' },
    ])

    const checks = ref([
      { item: 'AK 密钥不可猜测', ok: true, detail: 'ak- + 20 hex 随机字符 (80 bits)' },
      { item: 'AK Secret 不传输 (HMAC模式)', ok: true, detail: 'HMAC-SHA256 签名模式已就绪 (需AES加密存储)' },
      { item: 'AK Secret bcrypt 存储', ok: true, detail: 'DelegatingPasswordEncoder + bcrypt (cost=10)' },
      { item: 'Admin Token bcrypt 验证', ok: true, detail: 'passwordEncoder.matches() timing-safe' },
      { item: 'DCR 两层客户端模型', ok: true, detail: 'DCR 客户端禁止 client_credentials' },
      { item: 'PKCE 公共客户端', ok: true, detail: 'mcp-weather-client, mcp-climate-client' },
      { item: 'Per-service PRM', ok: true, detail: 'RFC 9728 每服务 Protected Resource Metadata' },
      { item: 'RFC 9728 host 匹配', ok: true, detail: 'PRM resource 动态匹配请求 host' },
      { item: 'CORS Origin:null', ok: true, detail: '浏览器表单 POST 不被拒' },
      { item: 'Session Cookie 限制', ok: true, detail: 'Path=/api-gateway/ecso/auth, SameSite=Lax, HttpOnly' },
      { item: 'TLS (HTTPS)', ok: false, detail: '开发环境未启用 — 生产必须开启', warn: true },
      { item: '内部端口绑定 127.0.0.1', ok: false, detail: '9090/9092/9093 仍监听 0.0.0.0 — 生产需限制', warn: true },
      { item: '默认 admin 密码', ok: false, detail: 'admin/admin 仅开发用 — 生产需修改', warn: true },
    ])

    return { securityItems, checks }
  }
}
</script>

<style scoped>
.security-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}
.sec-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f8f9ff;
}
.sec-item.ok { background: #f1f8f1; }
.sec-item.warn { background: #fff8e1; }
.sec-item.red { background: #fff3f3; }
.sec-icon { font-size: 18px; }
.sec-label { flex: 1; font-size: 12px; font-weight: 500; }

.flow-container { padding: 12px 0; }
.flow-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-bottom: 20px;
}
.flow-box {
  border-radius: 8px;
  padding: 12px 20px;
  text-align: center;
  min-width: 100px;
}
.flow-box strong { display: block; font-size: 13px; }
.flow-box small { font-size: 11px; color: #888; }
.flow-box.client { background: #e3f2fd; border: 1px solid #90caf9; }
.flow-box.gateway { background: #e8f5e9; border: 1px solid #a5d6a7; }
.flow-box.backend { background: #fff3e0; border: 1px solid #ffe082; }

.flow-arrow { display: flex; flex-direction: column; align-items: center; gap: 4px; }
.arrow-line { width: 40px; height: 2px; background: #888; }
.arrow-label { font-size: 10px; color: #888; }

.flow-detail { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.flow-branch { border-radius: 8px; padding: 14px; }
.ak-branch { background: #fff8e1; border: 1px solid #ffe082; }
.oauth-branch { background: #e8f5e9; border: 1px solid #a5d6a7; }
.flow-branch h4 { font-size: 13px; margin-bottom: 8px; }
.flow-steps { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.step {
  background: white;
  padding: 3px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-family: monospace;
}
.step.ok { background: #c8e6c9; }
.step-arrow { color: #888; font-size: 14px; }
</style>
