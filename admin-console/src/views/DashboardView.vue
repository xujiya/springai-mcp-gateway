<template>
  <div class="dashboard">
    <!-- Stats Row -->
    <div class="stats-row">
      <div class="stat-card">
        <span class="stat-icon">🔑</span>
        <div class="stat-info">
          <span class="stat-num">{{ stats.akTotal }}</span>
          <span class="stat-label">API Key</span>
        </div>
        <span class="stat-sub">启用 {{ stats.akEnabled }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-icon">📋</span>
        <div class="stat-info">
          <span class="stat-num">{{ stats.clientTotal }}</span>
          <span class="stat-label">OAuth 客户端</span>
        </div>
        <span class="stat-sub">预注册 {{ stats.clientPrereg }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-icon">👤</span>
        <div class="stat-info">
          <span class="stat-num">{{ stats.userTotal }}</span>
          <span class="stat-label">系统用户</span>
        </div>
        <span class="stat-sub">启用 {{ stats.userEnabled }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-icon">📡</span>
        <div class="stat-info">
          <span class="stat-num">{{ stats.serviceUp }}/{{ stats.serviceTotal }}</span>
          <span class="stat-label">MCP 服务</span>
        </div>
        <span class="stat-sub" :class="stats.serviceUp === stats.serviceTotal ? 'ok' : 'err'">
          {{ stats.serviceUp === stats.serviceTotal ? '全部正常' : '有服务离线' }}
        </span>
      </div>
    </div>

    <!-- Two Column -->
    <div class="two-col">
      <!-- Left: Services -->
      <div class="card">
        <h3>📡 MCP 服务状态</h3>
        <div class="service-list">
          <div v-for="svc in services" :key="svc.name" :class="['service-row', svc.status]">
            <span class="svc-icon">{{ svc.icon }}</span>
            <span class="svc-name">{{ svc.name }}</span>
            <span :class="['badge', svc.status === 'up' ? 'green' : svc.status === 'down' ? 'red' : 'gray']">
              {{ svc.status === 'up' ? '运行中' : svc.status === 'down' ? '离线' : '检测中' }}
            </span>
            <span class="svc-tools">{{ svc.tools.join(', ') }}</span>
          </div>
        </div>
      </div>

      <!-- Right: Recent Activity -->
      <div class="card">
        <h3>📝 最近活动</h3>
        <div class="activity-list">
          <div v-for="a in activities" :key="a.time" class="activity-row">
            <span class="act-icon">{{ a.icon }}</span>
            <span class="act-text">{{ a.text }}</span>
            <span class="act-time">{{ a.time }}</span>
          </div>
          <div v-if="!activities.length" class="empty-hint">暂无活动记录</div>
        </div>
      </div>
    </div>

    <!-- Auth Model Overview -->
    <div class="card">
      <h3>🔐 认证模式概览</h3>
      <div class="auth-models">
        <div class="model-box ak">
          <h4>🔑 AK 静态凭证</h4>
          <div class="model-stat">
            <span>活跃 Key: <strong>{{ stats.akEnabled }}</strong></span>
            <span>全局权限: <strong>{{ stats.akGlobal }}</strong></span>
          </div>
          <p class="model-desc">长期有效 · 无需浏览器 · 对标阿里云 AccessKey</p>
        </div>
        <div class="model-box oauth">
          <h4>🔐 OAuth2 + PKCE</h4>
          <div class="model-stat">
            <span>公开客户端: <strong>{{ stats.clientPublic }}</strong></span>
            <span>机密客户端: <strong>{{ stats.clientConfidential }}</strong></span>
          </div>
          <p class="model-desc">短期 Token · 浏览器 PKCE · Access 24h / Refresh 30d</p>
        </div>
        <div class="model-box dcr">
          <h4>📝 DCR 动态注册</h4>
          <div class="model-stat">
            <span>DCR 客户端: <strong>{{ stats.clientDcr }}</strong></span>
            <span>禁止 client_credentials: <strong>✅</strong></span>
          </div>
          <p class="model-desc">{{ stats.dcrEnabled ? 'DCR 开放中' : 'DCR 已关闭 (预注册模式)' }}</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, reactive, onMounted } from 'vue'
import { listApiKeys, listClients, listUsers, checkMcpService } from '../api/admin.js'

export default {
  name: 'DashboardView',
  setup() {
    const stats = reactive({
      akTotal: 0, akEnabled: 0, akGlobal: 0,
      clientTotal: 0, clientPrereg: 0, clientPublic: 0, clientConfidential: 0, clientDcr: 0,
      userTotal: 0, userEnabled: 0,
      serviceUp: 0, serviceTotal: 2,
      dcrEnabled: true,
    })

    const services = ref([
      { name: 'weather', icon: '🌤️', tools: ['getAlerts', 'getWeatherForecast'], status: 'checking' },
      { name: 'climate', icon: '🌊', tools: ['getStormWarnings', 'getClimateForecast'], status: 'checking' },
    ])

    const activities = ref([])

    const PREREG = ['springai-gateway-client', 'mcp-weather-client', 'mcp-climate-client']

    async function load() {
      // API Keys
      try {
        const keys = await listApiKeys()
        stats.akTotal = keys.length
        stats.akEnabled = keys.filter(k => k.enabled).length
        stats.akGlobal = keys.filter(k => k.serviceScope === '*').length
      } catch {}

      // Clients
      try {
        const clients = await listClients()
        stats.clientTotal = clients.length
        stats.clientPrereg = clients.filter(c => PREREG.includes(c.clientId)).length
        stats.clientDcr = clients.filter(c => !PREREG.includes(c.clientId)).length
        stats.clientPublic = clients.filter(c => !c.hasSecret).length
        stats.clientConfidential = clients.filter(c => c.hasSecret).length
      } catch {}

      // Users
      try {
        const users = await listUsers()
        stats.userTotal = users.length
        stats.userEnabled = users.filter(u => u.enabled).length
      } catch {}

      // Services
      let up = 0
      for (const svc of services.value) {
        const ok = await checkMcpService(svc.name)
        svc.status = ok ? 'up' : 'down'
        if (ok) up++
      }
      stats.serviceUp = up

      // Recent activity (from timestamps)
      const acts = []
      try {
        const keys = await listApiKeys()
        keys.sort((a, b) => (b.createdAt || '').localeCompare(a.createdAt || ''))
        for (const k of keys.slice(0, 3)) {
          acts.push({ icon: '🔑', text: `AK "${k.name}" 创建`, time: formatRelative(k.createdAt) })
        }
      } catch {}
      activities.value = acts
    }

    function formatRelative(iso) {
      if (!iso) return ''
      const diff = Date.now() - new Date(iso).getTime()
      if (diff < 60000) return '刚刚'
      if (diff < 3600000) return Math.floor(diff / 60000) + '分钟前'
      if (diff < 86400000) return Math.floor(diff / 3600000) + '小时前'
      return Math.floor(diff / 86400000) + '天前'
    }

    onMounted(load)
    return { stats, services, activities }
  }
}
</script>

<style scoped>
.stats-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}
.stat-card {
  background: white;
  border-radius: 10px;
  padding: 16px 20px;
  display: flex;
  align-items: center;
  gap: 12px;
  box-shadow: 0 1px 6px rgba(0,0,0,0.06);
}
.stat-icon { font-size: 28px; }
.stat-info { flex: 1; }
.stat-num { display: block; font-size: 24px; font-weight: 700; color: #1a73e8; }
.stat-label { font-size: 12px; color: #888; }
.stat-sub { font-size: 11px; color: #888; }
.stat-sub.ok { color: #2e7d32; }
.stat-sub.err { color: #c62828; font-weight: 600; }

.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 16px;
}

.service-list { display: flex; flex-direction: column; gap: 8px; }
.service-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 6px;
  background: #fafafa;
}
.service-row.up { border-left: 3px solid #4caf50; }
.service-row.down { border-left: 3px solid #f44336; }
.svc-icon { font-size: 18px; }
.svc-name { font-weight: 600; flex: 1; }
.svc-tools { font-size: 11px; color: #888; }

.activity-list { display: flex; flex-direction: column; gap: 6px; }
.activity-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px solid #f5f5f5;
}
.act-icon { font-size: 14px; }
.act-text { flex: 1; font-size: 13px; }
.act-time { font-size: 11px; color: #aaa; }
.empty-hint { color: #999; font-size: 13px; padding: 12px; text-align: center; }

.auth-models {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 16px;
}
.model-box {
  border-radius: 8px;
  padding: 14px;
}
.model-box.ak { background: #fff8e1; border: 1px solid #ffe082; }
.model-box.oauth { background: #e8f5e9; border: 1px solid #a5d6a7; }
.model-box.dcr { background: #e3f2fd; border: 1px solid #90caf9; }
.model-box h4 { font-size: 13px; margin-bottom: 8px; }
.model-stat { display: flex; gap: 16px; font-size: 12px; margin-bottom: 6px; }
.model-desc { font-size: 11px; color: #666; }

@media (max-width: 900px) {
  .stats-row { grid-template-columns: 1fr 1fr; }
  .two-col { grid-template-columns: 1fr; }
  .auth-models { grid-template-columns: 1fr; }
}
</style>
