<template>
  <div class="services-view">
    <!-- Service Cards -->
    <div class="service-grid">
      <div v-for="svc in services" :key="svc.name" :class="['service-card', svc.status]">
        <div class="svc-header">
          <span class="svc-icon">{{ svc.icon }}</span>
          <h3>{{ svc.name }}</h3>
          <span :class="['badge', svc.status === 'up' ? 'green' : svc.status === 'checking' ? 'gray' : 'red']">
            {{ svc.status === 'up' ? '运行中' : svc.status === 'checking' ? '检测中...' : '离线' }}
          </span>
        </div>

        <div class="svc-info">
          <div class="info-row">
            <span class="info-label">MCP 端点</span>
            <code class="mono">/mcp-gateway/{{ svc.name }}/mcp</code>
          </div>
          <div class="info-row">
            <span class="info-label">后端地址</span>
            <code class="mono">{{ svc.backendUrl }}</code>
          </div>
          <div class="info-row">
            <span class="info-label">PRM 端点</span>
            <code class="mono">/{{ svc.name }}/.well-known/oauth-protected-resource</code>
          </div>
        </div>

        <!-- PRM Info -->
        <div v-if="svc.prm" class="prm-info">
          <h4>Protected Resource Metadata</h4>
          <div class="info-row">
            <span class="info-label">resource</span>
            <code class="mono small">{{ svc.prm.resource }}</code>
          </div>
          <div class="info-row">
            <span class="info-label">authorization_servers</span>
            <code class="mono small">{{ svc.prm.authorization_servers?.[0] }}</code>
          </div>
          <div class="info-row">
            <span class="info-label">scopes</span>
            <span v-for="s in svc.prm.scopes_supported" :key="s" class="badge blue" style="margin-right:4px">{{ s }}</span>
          </div>
        </div>

        <!-- Tools -->
        <div v-if="svc.tools.length" class="tools-section">
          <h4>可用工具</h4>
          <div class="tool-tags">
            <span v-for="t in svc.tools" :key="t" class="tool-tag">{{ t }}</span>
          </div>
        </div>

        <!-- Test Button -->
        <button class="btn-sm" @click="testService(svc)" :disabled="svc.testing">
          {{ svc.testing ? '测试中...' : '🧪 测试连通性' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, onMounted } from 'vue'
import { checkMcpService, getProtectedResourceMetadata } from '../api/admin.js'
import axios from 'axios'

const MCP_GW = '/api-gateway/ecso/mcp-gateway'

export default {
  name: 'ServicesView',
  setup() {
    const services = ref([
      {
        name: 'weather', icon: '🌤️',
        backendUrl: 'http://localhost:9092/mcp',
        tools: ['getAlerts', 'getWeatherForecast'],
        status: 'checking', testing: false, prm: null,
      },
      {
        name: 'climate', icon: '🌊',
        backendUrl: 'http://localhost:9093/mcp',
        tools: ['getStormWarnings', 'getClimateForecast'],
        status: 'checking', testing: false, prm: null,
      },
    ])

    async function checkService(svc) {
      svc.status = 'checking'
      const up = await checkMcpService(svc.name)
      svc.status = up ? 'up' : 'down'

      // Get PRM
      try {
        svc.prm = await getProtectedResourceMetadata(svc.name)
      } catch {}
    }

    async function testService(svc) {
      svc.testing = true
      try {
        // Try initialize without auth — expect 401
        const { status, headers } = await axios.post(
          `${MCP_GW}/${svc.name}/mcp`,
          { jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'test', version: '1.0' } } },
          { headers: { 'Content-Type': 'application/json' }, validateStatus: () => true }
        )
        if (status === 401) {
          const wwwAuth = headers['www-authenticate']
          alert(`✅ 服务正常！\n状态: ${status} (认证要求)\nWWW-Authenticate: ${wwwAuth?.substring(0, 80)}...`)
        } else if (status === 200) {
          alert(`✅ 服务正常！无认证要求 (HTTP ${status})`)
        } else {
          alert(`⚠️ 异常响应: HTTP ${status}`)
        }
      } catch (err) {
        alert(`❌ 连接失败: ${err.message}`)
      } finally {
        svc.testing = false
      }
    }

    onMounted(() => {
      services.value.forEach(checkService)
    })

    return { services, testService }
  }
}
</script>

<style scoped>
.service-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.service-card {
  background: white;
  border-radius: 10px;
  padding: 20px;
  box-shadow: 0 1px 6px rgba(0,0,0,0.06);
  border-left: 4px solid #e0e0e0;
}
.service-card.up { border-left-color: #4caf50; }
.service-card.down { border-left-color: #f44336; }

.svc-header { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.svc-icon { font-size: 24px; }
.svc-header h3 { flex: 1; font-size: 16px; }

.svc-info { margin-bottom: 12px; }
.info-row { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.info-label { font-size: 11px; color: #888; min-width: 100px; font-weight: 500; }
.small { font-size: 11px; }

.prm-info {
  background: #f8f9ff;
  border-radius: 6px;
  padding: 10px;
  margin-bottom: 12px;
}
.prm-info h4 { font-size: 12px; color: #555; margin-bottom: 6px; }

.tools-section { margin-bottom: 12px; }
.tools-section h4 { font-size: 12px; color: #888; margin-bottom: 6px; }
.tool-tags { display: flex; gap: 6px; flex-wrap: wrap; }
.tool-tag {
  background: #e3f2fd;
  color: #1565c0;
  padding: 3px 10px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 500;
}
</style>
</template>
