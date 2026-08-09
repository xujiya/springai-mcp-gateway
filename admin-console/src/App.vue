<template>
  <div class="app">
    <!-- Header -->
    <header class="header">
      <div class="header-left">
        <span class="logo">🛡️</span>
        <h1>ECSO MCP 管理控制台</h1>
      </div>
      <div class="header-right">
        <span class="auth-mode-badge">🔐 双认证模式</span>
        <button v-if="isLoggedIn" class="btn-logout" @click="logout">退出</button>
      </div>
    </header>

    <!-- Login Gate -->
    <div v-if="!isLoggedIn" class="login-gate">
      <div class="login-card">
        <h2>管理员登录</h2>
        <p class="login-hint">请输入 Admin Token 访问管理控制台</p>
        <div class="form-group">
          <label>Admin Token</label>
          <input
            v-model="loginToken"
            type="password"
            placeholder="adm-xxxxxxxx..."
            @keyup.enter="login"
            class="input-token"
          />
        </div>
        <button class="btn-primary" @click="login" :disabled="!loginToken">
          登录
        </button>
        <p v-if="loginError" class="error">{{ loginError }}</p>
      </div>
    </div>

    <!-- Main Content -->
    <div v-else class="main">
      <!-- Tab Navigation -->
      <nav class="tabs">
        <button
          v-for="tab in tabs"
          :key="tab.id"
          :class="['tab', { active: activeTab === tab.id }]"
          @click="activeTab = tab.id"
        >
          {{ tab.icon }} {{ tab.label }}
        </button>
      </nav>

      <!-- Tab Content -->
      <div class="tab-content">
        <ApiKeysView v-if="activeTab === 'ak'" />
        <OAuthView v-if="activeTab === 'oauth'" />
        <ServicesView v-if="activeTab === 'services'" />
        <SecurityView v-if="activeTab === 'security'" />
      </div>
    </div>
  </div>
</template>

<script>
import { ref } from 'vue'
import { setAdminToken, clearAdminToken, getAdminToken, listApiKeys } from './api/admin.js'
import ApiKeysView from './views/ApiKeysView.vue'
import OAuthView from './views/OAuthView.vue'
import ServicesView from './views/ServicesView.vue'
import SecurityView from './views/SecurityView.vue'

export default {
  name: 'App',
  components: { ApiKeysView, OAuthView, ServicesView, SecurityView },
  setup() {
    const isLoggedIn = ref(!!getAdminToken())
    const loginToken = ref('')
    const loginError = ref('')
    const activeTab = ref('ak')

    const tabs = [
      { id: 'ak',       icon: '🔑', label: 'AK 静态凭证' },
      { id: 'oauth',    icon: '🔐', label: 'OAuth 客户端' },
      { id: 'services',  icon: '📡', label: 'MCP 服务' },
      { id: 'security', icon: '🛡️', label: '安全状态' },
    ]

    async function login() {
      loginError.value = ''
      try {
        setAdminToken(loginToken.value)
        await listApiKeys()  // Verify token works
        isLoggedIn.value = true
      } catch {
        clearAdminToken()
        loginError.value = 'Token 无效，请检查后重试'
      }
    }

    function logout() {
      clearAdminToken()
      isLoggedIn.value = false
    }

    return { isLoggedIn, loginToken, loginError, login, logout, activeTab, tabs }
  }
}
</script>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background: #f0f2f5;
  color: #1d1d1f;
  min-height: 100vh;
}

.app { min-height: 100vh; display: flex; flex-direction: column; }

/* Header */
.header {
  background: linear-gradient(135deg, #1a73e8, #0d47a1);
  color: white;
  padding: 0 24px;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-shadow: 0 2px 8px rgba(0,0,0,0.15);
}
.header-left { display: flex; align-items: center; gap: 12px; }
.header-left h1 { font-size: 18px; font-weight: 600; }
.logo { font-size: 24px; }
.header-right { display: flex; align-items: center; gap: 12px; }
.auth-mode-badge {
  background: rgba(255,255,255,0.2);
  padding: 4px 12px;
  border-radius: 12px;
  font-size: 12px;
}
.btn-logout {
  background: rgba(255,255,255,0.15);
  border: 1px solid rgba(255,255,255,0.3);
  color: white;
  padding: 6px 16px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
}
.btn-logout:hover { background: rgba(255,255,255,0.25); }

/* Login Gate */
.login-gate {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}
.login-card {
  background: white;
  border-radius: 12px;
  padding: 40px;
  width: 400px;
  box-shadow: 0 4px 24px rgba(0,0,0,0.1);
}
.login-card h2 { margin-bottom: 8px; font-size: 22px; }
.login-hint { color: #666; font-size: 14px; margin-bottom: 24px; }
.input-token {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 14px;
  font-family: monospace;
  margin-top: 6px;
}
.input-token:focus { outline: none; border-color: #1a73e8; box-shadow: 0 0 0 3px rgba(26,115,232,0.1); }

/* Main Layout */
.main { flex: 1; display: flex; flex-direction: column; }

/* Tabs */
.tabs {
  display: flex;
  background: white;
  border-bottom: 1px solid #e0e0e0;
  padding: 0 24px;
  gap: 4px;
}
.tab {
  padding: 12px 20px;
  border: none;
  background: none;
  cursor: pointer;
  font-size: 14px;
  font-weight: 500;
  color: #666;
  border-bottom: 2px solid transparent;
  transition: all 0.2s;
}
.tab:hover { color: #1a73e8; }
.tab.active { color: #1a73e8; border-bottom-color: #1a73e8; }

.tab-content { flex: 1; padding: 24px; }

/* Common Styles */
.btn-primary {
  width: 100%;
  padding: 10px 20px;
  background: #1a73e8;
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  margin-top: 16px;
}
.btn-primary:hover { background: #1557b0; }
.btn-primary:disabled { background: #ccc; cursor: not-allowed; }

.error { color: #d32f2f; font-size: 13px; margin-top: 8px; }
.form-group { margin-bottom: 16px; }
.form-group label { display: block; font-size: 13px; font-weight: 500; margin-bottom: 4px; color: #555; }

.card {
  background: white;
  border-radius: 10px;
  padding: 20px;
  box-shadow: 0 1px 6px rgba(0,0,0,0.06);
  margin-bottom: 16px;
}
.card h3 { font-size: 16px; font-weight: 600; margin-bottom: 12px; display: flex; align-items: center; gap: 8px; }

.btn-sm {
  padding: 6px 14px;
  border: 1px solid #ddd;
  background: white;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
}
.btn-sm:hover { background: #f5f5f5; }
.btn-sm.danger { color: #d32f2f; border-color: #ffcdd2; }
.btn-sm.danger:hover { background: #fff5f5; }
.btn-sm.success { color: #2e7d32; border-color: #c8e6c9; }
.btn-sm.success:hover { background: #f1fff1; }

.badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
}
.badge.green  { background: #e8f5e9; color: #2e7d32; }
.badge.red    { background: #ffebee; color: #c62828; }
.badge.blue   { background: #e3f2fd; color: #1565c0; }
.badge.orange { background: #fff3e0; color: #e65100; }
.badge.gray   { background: #f5f5f5; color: #666; }

table { width: 100%; border-collapse: collapse; }
th { text-align: left; padding: 10px 12px; font-size: 12px; color: #888; font-weight: 600; border-bottom: 1px solid #eee; }
td { padding: 10px 12px; font-size: 13px; border-bottom: 1px solid #f5f5f5; }
tr:hover td { background: #fafafa; }

.mono { font-family: 'SF Mono', Consolas, monospace; font-size: 12px; }
</style>
</template>
