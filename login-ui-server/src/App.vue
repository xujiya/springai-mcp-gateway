<template>
  <div class="card">
    <div class="logo">
      <h1>🔐 MCP Auth Server</h1>
      <div class="subtitle">OAuth2 + DCR for MCP Gateway</div>
    </div>

    <!-- 授权上下文 -->
    <div v-if="authInfo.pending" class="auth-context">
      <div class="context-title">授权请求</div>
      <div class="context-row">
        <span class="context-label">应用</span>
        <span class="context-value">{{ authInfo.clientName }}</span>
      </div>
      <div class="context-row">
        <span class="context-label">Client ID</span>
        <span class="context-value mono">{{ authInfo.clientId }}</span>
      </div>
      <div class="context-row" v-if="authInfo.scope">
        <span class="context-label">权限</span>
        <span class="context-value">
          <span v-for="s in authInfo.scope.split(' ')" :key="s" class="scope-tag">{{ s }}</span>
        </span>
      </div>
    </div>
    <div v-else-if="authInfoLoaded" class="auth-context empty">
      直接访问登录页（无待授权请求）
    </div>

    <form :action="loginAction" method="POST">
      <div class="field">
        <label>用户名</label>
        <input v-model="username" name="username" type="text" placeholder="admin" autocomplete="username" />
      </div>
      <div class="field">
        <label>密码</label>
        <input v-model="password" name="password" type="password" placeholder="admin" autocomplete="current-password" />
      </div>
      <div v-if="isError" class="error-msg">❌ 用户名或密码错误</div>
      <button class="btn" type="submit">登 录</button>
    </form>

    <div class="info-box">
      <div style="font-weight:600; margin-bottom:6px;">MCP OAuth2 + DCR 流程</div>
      <div>① MCP 客户端 → <code>/mcp</code> → 401 + resource_metadata</div>
      <div>② 发现 AS → <code>/.well-known/openid-configuration</code></div>
      <div>③ DCR 自动注册 → <code>/oauth2/register</code></div>
      <div>④ 用户登录授权（本页面）+ PKCE</div>
      <div>⑤ Authorization Code 换 Token → 访问 MCP</div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'

const username = ref('admin')
const password = ref('admin')
const isError = ref(window.location.search.includes('error'))
const authInfo = ref({ pending: false })
const authInfoLoaded = ref(false)

// Detect if running behind a gateway (path contains /ecso).
// Derive the gateway prefix from the pathname so it works behind nginx
// (/api-gateway/ecso/...) as well as a direct mount (/ecso/...).
const _pathname = window.location.pathname
const _idx = _pathname.indexOf('/ecso')
const gatewayPrefix = _idx >= 0 ? _pathname.slice(0, _idx) + '/ecso/auth' : ''
const loginAction = gatewayPrefix + '/login'

onMounted(async () => {
  try {
    const resp = await fetch(gatewayPrefix + '/oauth2/auth-info')
    authInfo.value = await resp.json()
  } catch (e) {
    authInfo.value = { pending: false }
  }
  authInfoLoaded.value = true
})
</script>
