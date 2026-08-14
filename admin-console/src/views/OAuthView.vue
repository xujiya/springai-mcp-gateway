<template>
  <div class="oauth-view">
    <!-- AS Metadata -->
    <div class="card">
      <h3>🏛️ 授权服务器 (Authorization Server)</h3>
      <div v-if="asMetadata" class="meta-grid">
        <div class="meta-item" v-for="(val, key) in displayMetadata" :key="key">
          <span class="meta-key">{{ key }}</span>
          <a v-if="isUrl(val)" :href="val" class="meta-val mono link">{{ val }}</a>
          <span v-else class="meta-val mono">{{ val }}</span>
        </div>
      </div>
      <p v-else class="empty">加载中...</p>
    </div>

    <!-- Pre-registered Clients -->
    <div class="card">
      <h3>📋 预注册客户端 (对标阿里云模式)</h3>
      <table>
        <thead>
          <tr>
            <th>客户端 ID</th>
            <th>名称</th>
            <th>认证方式</th>
            <th>授权类型</th>
            <th>PKCE</th>
            <th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="client in clients" :key="client.id">
            <td><code class="mono">{{ client.clientId }}</code></td>
            <td>{{ client.name }}</td>
            <td>
              <span v-for="m in client.authMethods" :key="m" :class="['badge', m === 'none' ? 'orange' : 'blue']">
                {{ m }}
              </span>
            </td>
            <td>
              <span v-for="g in client.grants" :key="g" :class="['badge', g === 'client_credentials' ? 'green' : 'blue']">
                {{ g }}
              </span>
            </td>
            <td>
              <span :class="['badge', client.pkce ? 'green' : 'gray']">
                {{ client.pkce ? '✅ 必需' : '❌ 不需要' }}
              </span>
            </td>
            <td><span class="badge green">活跃</span></td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Dual Auth Model Explanation -->
    <div class="card info-card">
      <h3>💡 双认证模式说明</h3>
      <div class="dual-model">
        <div class="model-box ak-model">
          <h4>🔑 AK 静态凭证</h4>
          <p class="model-desc">长期有效，无需浏览器，对标阿里云 AccessKey</p>
          <ul>
            <li><strong>AccessKey ID</strong> — 公开，用于查找</li>
            <li><strong>AccessKey Secret</strong> — 私密，永不传输</li>
            <li>适用: CI/CD、AI Agent 无人值守</li>
            <li>过期: 默认永不过期</li>
          </ul>
        </div>
        <div class="model-box oauth-model">
          <h4>🔐 OAuth 交互认证</h4>
          <p class="model-desc">短期 Token，浏览器 PKCE 登录</p>
          <ul>
            <li><strong>authorization_code</strong> — 浏览器跳转</li>
            <li><strong>PKCE</strong> — 公共客户端安全</li>
            <li>适用: Cursor、Cherry Studio 桌面客户端</li>
            <li>过期: Access 24h, Refresh 30d</li>
          </ul>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, onMounted, computed } from 'vue'
import { getAuthServerMetadata, listClients } from '../api/admin.js'

export default {
  name: 'OAuthView',
  setup() {
    const asMetadata = ref(null)

    const clients = ref([])

    async function loadClients() {
      try {
        const all = await listClients()
        clients.value = all.map(c => ({
          id: c.clientId,
          clientId: c.clientId,
          name: c.clientName || c.clientId,
          authMethods: c.grantTypes?.includes('client_credentials') ? ['client_secret_post', 'client_secret_basic'] : ['none'],
          grants: (c.grantTypes || '').split(',').map(s => s.trim()).filter(Boolean),
          pkce: true,
        }))
      } catch {}
    }

    const displayMetadata = computed(() => {
      if (!asMetadata.value) return {}
      const m = asMetadata.value
      return {
        'Issuer': m.issuer,
        '授权端点': m.authorization_endpoint,
        'Token 端点': m.token_endpoint,
        'JWKS': m.jwks_uri,
        '注册端点': m.registration_endpoint || '(DCR 已关闭)',
        '吊销端点': m.revocation_endpoint,
        'PKCE 方法': m.code_challenge_methods_supported?.join(', '),
      }
    })

    function isUrl(val) {
      return typeof val === 'string' && val.startsWith('http')
    }

    onMounted(async () => {
      try { asMetadata.value = await getAuthServerMetadata() } catch {}
      await loadClients()
    })

    return { asMetadata, displayMetadata, clients, isUrl }
  }
}
</script>

<style scoped>
.meta-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(350px, 1fr)); gap: 8px; }
.meta-item { display: flex; gap: 8px; padding: 6px 0; border-bottom: 1px solid #f5f5f5; }
.meta-key { font-size: 12px; color: #888; min-width: 80px; font-weight: 500; }
.meta-val { font-size: 12px; word-break: break-all; }
.link { color: #1a73e8; text-decoration: none; }
.link:hover { text-decoration: underline; }

.info-card { background: #f8f9ff; }
.dual-model { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-top: 12px; }
.model-box { border-radius: 8px; padding: 16px; }
.ak-model { background: #fff8e1; border: 1px solid #ffe082; }
.oauth-model { background: #e8f5e9; border: 1px solid #a5d6a7; }
.model-box h4 { margin-bottom: 8px; font-size: 14px; }
.model-desc { font-size: 12px; color: #666; margin-bottom: 8px; }
.model-box ul { padding-left: 16px; font-size: 12px; }
.model-box li { margin-bottom: 4px; }
.empty { text-align: center; color: #999; padding: 20px; }
</style>
