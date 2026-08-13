<template>
  <div class="auth-container">
    <!-- ===== Consent 视图（先于登录：展示授权信息 → 同意并登录）===== -->
    <template v-if="isConsent">
      <div class="logo-container">
        <div class="logo">🔐</div>
        <div class="mcp-title">MCP</div>
      </div>
      <h1>授权确认</h1>
      <p class="subtitle">{{ consent.clientName }} 请求访问您的账户</p>

      <div class="client-info">
        <h3>客户端应用</h3>
        <div class="client-id">{{ consent.clientId }}</div>
      </div>

      <div class="scope-info" v-if="consent.scope">
        <h3>请求权限</h3>
        <div class="scope-list">
          <span v-for="s in consent.scope.split(' ')" :key="s" class="scope-tag">{{ s }}</span>
        </div>
      </div>

      <div class="auth-flow-info" v-if="consent.redirectUri">
        <h3>回调地址</h3>
        <p class="redirect-uri">{{ consent.redirectUri }}</p>
        <p class="flow-desc">同意后将重定向到登录页面完成认证，认证通过后获得访问权限。</p>
      </div>

      <form :action="consentAction" method="POST">
        <input type="hidden" name="client_id" :value="consent.clientId" />
        <input type="hidden" name="return_to" :value="consent.fullAuthUrl" />
        <button class="btn-primary" type="submit">同意并登录</button>
      </form>

      <div class="branding">模型上下文协议 (MCP) 服务器</div>
    </template>

    <!-- ===== Login 视图（只留登录表单，无授权信息）===== -->
    <template v-else>
      <div class="logo-container">
        <div class="logo">🔐</div>
        <div class="mcp-title">MCP</div>
      </div>
      <h1>用户登录</h1>
      <p class="subtitle">请输入用户名和密码完成认证</p>

      <div v-if="isError" class="error">❌ 用户名或密码错误</div>

      <form :action="loginAction" method="POST">
        <label>用户名</label>
        <input v-model="username" name="username" type="text" placeholder="admin" autocomplete="username" />
        <label>密码</label>
        <input v-model="password" name="password" type="password" placeholder="admin" autocomplete="current-password" />
        <button class="btn-primary" type="submit">登 录</button>
      </form>

      <div class="branding">模型上下文协议 (MCP) 服务器</div>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'

const username = ref('admin')
const password = ref('admin')
const isError = ref(window.location.search.includes('error'))

// Detect gateway prefix from pathname (/api-gateway/ecso/... or /ecso/...)
const _pathname = window.location.pathname
const _idx = _pathname.indexOf('/ecso')
const gatewayPrefix = _idx >= 0 ? _pathname.slice(0, _idx) + '/ecso/auth' : ''
const loginAction = gatewayPrefix + '/login'

// ── Consent 视图：路径含 /consent 即为 consent 页 ──
const isConsent = _pathname.includes('/consent')
const consent = ref({ clientName: '加载中...', clientId: '', scope: '', redirectUri: '', fullAuthUrl: '' })
const consentAction = gatewayPrefix + '/oauth2/consent'

onMounted(async () => {
  if (!isConsent) return  // login 视图无需拉取授权上下文

  const params = new URLSearchParams(window.location.search)
  const returnTo = params.get('return_to') || ''
  const clientId = params.get('client_id') || ''
  let scope = '', redirectUri = ''
  // 绝对公网 URL：sendRedirect 需要绝对 URL 才能跳回公网入口
  let fullAuthUrl = returnTo
  try {
    const returnUrl = new URL(returnTo, window.location.origin)
    scope = returnUrl.searchParams.get('scope') || ''
    redirectUri = returnUrl.searchParams.get('redirect_uri') || ''
    fullAuthUrl = window.location.origin + gatewayPrefix + returnUrl.pathname + returnUrl.search + returnUrl.hash
  } catch (e) { /* returnTo 非 URL，保持原值 */ }
  consent.value = { clientName: clientId, clientId, scope, redirectUri, fullAuthUrl }
  // 拉取客户端展示名
  try {
    const resp = await fetch(gatewayPrefix + '/oauth2/consent-info?client_id=' + encodeURIComponent(clientId))
    if (resp.ok) {
      const info = await resp.json()
      consent.value.clientName = info.clientName || clientId
    }
  } catch (e) { /* fallback to clientId */ }
})
</script>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
  background: linear-gradient(135deg, #1e3a5f, #2563eb 50%, #1e40af);
  color: #ffffff;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}
.auth-container {
  background: #ffffff;
  color: #000000;
  border-radius: 16px;
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.2);
  padding: 40px;
  max-width: 500px;
  width: 100%;
  text-align: center;
  border: 1px solid #e2e8f0;
}
.logo-container { margin-bottom: 32px; }
.logo {
  width: 80px; height: 80px; margin: 0 auto 16px;
  border-radius: 16px;
  display: flex; align-items: center; justify-content: center;
  font-size: 40px;
  background: linear-gradient(135deg, #2563eb, #1e40af);
  box-shadow: 0 4px 16px rgba(37, 99, 235, 0.3);
}
.mcp-title {
  font-size: 24px; font-weight: 700; color: #000;
  margin-bottom: 8px; letter-spacing: 2px;
}
h1 {
  color: #000; font-size: 32px; font-weight: 800;
  margin-bottom: 12px; line-height: 1.2;
}
.subtitle {
  color: #4a5568; font-size: 18px;
  margin-bottom: 32px; line-height: 1.5;
}
.client-info, .scope-info, .auth-flow-info {
  background: #f8f9fa;
  border-radius: 12px;
  padding: 24px;
  margin-bottom: 24px;
  text-align: left;
}
.client-info { border: 2px solid #e2e8f0; }
.auth-flow-info { border-left: 4px solid #2563eb; }
.client-info h3, .scope-info h3, .auth-flow-info h3 {
  color: #2d3748; font-size: 18px; font-weight: 600;
  margin-bottom: 12px;
}
.client-id {
  background: white; border: 2px solid #e2e8f0; border-radius: 8px;
  padding: 12px; font-family: 'Courier New', monospace;
  font-size: 14px; color: #4a5568; word-break: break-all;
}
.scope-list { display: flex; flex-wrap: wrap; gap: 8px; }
.scope-tag {
  background: linear-gradient(135deg, #2563eb, #1e40af);
  color: #fff; padding: 6px 14px; border-radius: 16px;
  font-size: 13px; font-weight: 600;
}
.redirect-uri {
  font-family: 'Courier New', monospace; font-size: 13px;
  color: #4a5568; word-break: break-all; margin-bottom: 8px;
}
.flow-desc { color: #4a5568; font-size: 14px; line-height: 1.5; }
label {
  display: block; text-align: left;
  color: #4a5568; font-size: 14px; font-weight: 600;
  margin-bottom: 6px;
}
input[type=text], input[type=password] {
  width: 100%; padding: 12px 16px;
  border: 2px solid #e2e8f0; border-radius: 8px;
  font-size: 15px; margin-bottom: 20px;
  transition: border-color 0.2s;
}
input:focus { outline: none; border-color: #2563eb; }
.btn-primary {
  width: 100%; padding: 18px 36px;
  background: linear-gradient(135deg, #2563eb, #1e40af);
  color: #ffffff; border: none; border-radius: 8px;
  font-size: 18px; font-weight: 700; cursor: pointer;
  transition: all 0.2s ease; letter-spacing: 1px;
}
.btn-primary:hover {
  background: linear-gradient(135deg, #1d4ed8, #1e3a8a);
  transform: translateY(-2px);
  box-shadow: 0 8px 25px rgba(37, 99, 235, 0.4);
}
.error {
  background: #fed7d7; color: #c53030; border-radius: 8px;
  padding: 12px 16px; margin-bottom: 20px;
  font-size: 14px; border-left: 4px solid #c53030;
}
.branding {
  margin-top: 24px; padding-top: 24px;
  border-top: 1px solid #e2e8f0;
  color: #718096; font-size: 12px;
}
</style>
