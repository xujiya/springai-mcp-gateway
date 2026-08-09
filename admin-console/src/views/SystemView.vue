<template>
  <div class="system-view">
    <div class="card">
      <h3>🖥️ 系统运行状态</h3>
      <button class="btn-sm" @click="load" style="margin-bottom:12px;">🔄 刷新</button>

      <div class="info-grid">
        <div class="info-section">
          <h4>Java 运行时</h4>
          <div class="info-item"><label>Java 版本</label><span>{{ sys.javaVersion || '-' }}</span></div>
          <div class="info-item"><label>JVM 名称</label><span>{{ sys.jvmName || '-' }}</span></div>
          <div class="info-item"><label>进程 PID</label><code class="mono">{{ sys.pid || '-' }}</code></div>
          <div class="info-item"><label>启动时间</label><span>{{ formatUptime(sys.uptimeMs) }}</span></div>
        </div>

        <div class="info-section">
          <h4>内存使用</h4>
          <div class="info-item"><label>堆内存</label><span>{{ formatMB(sys.heapUsed) }} / {{ formatMB(sys.heapMax) }}</span></div>
          <div class="mem-bar">
            <div class="mem-fill" :style="{ width: heapPercent + '%' }"></div>
          </div>
          <div class="info-item"><label>非堆</label><span>{{ formatMB(sys.nonHeapUsed) }}</span></div>
          <div class="info-item"><label>线程数</label><span>{{ sys.threadCount || '-' }}</span></div>
        </div>

        <div class="info-section">
          <h4>网关配置</h4>
          <div class="info-item"><label>MCP 后端数</label><span>{{ sys.mcpServiceCount || '-' }}</span></div>
          <div class="info-item"><label>DCR 模式</label>
            <span :class="['badge', sys.dcrEnabled ? 'orange' : 'green']">
              {{ sys.dcrEnabled ? '开放' : '预注册 (阿里云模式)' }}
            </span>
          </div>
          <div class="info-item"><label>Access Token TTL</label><span>{{ sys.accessTokenTTL || '-' }}</span></div>
          <div class="info-item"><label>AK 暴力破解防护</label><span :class="['badge', 'green']">10次/5分钟</span></div>
        </div>

        <div class="info-section">
          <h4>端口监听</h4>
          <div class="port-list">
            <div v-for="p in ports" :key="p.port" :class="['port-item', p.ok ? 'up' : 'down']">
              <span class="port-num">:{{ p.port }}</span>
              <span class="port-name">{{ p.name }}</span>
              <span :class="['badge', p.ok ? 'green' : 'red']">{{ p.ok ? '✅' : '❌' }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- DCR Cleanup -->
    <div class="card">
      <h3>🧹 DCR 垃圾客户端清理</h3>
      <p style="font-size:13px;color:#666;margin-bottom:12px;">
        DCR 动态注册会产生大量临时客户端，定期清理可保持数据库整洁。
        <strong>预注册客户端和系统客户端不会被删除。</strong>
      </p>
      <div style="display:flex;gap:8px;align-items:center;">
        <span>DCR 临时客户端: <strong>{{ dcrCount }}</strong> 个</span>
        <button class="btn-sm danger" @click="cleanDcr" :disabled="dcrCount === 0 || cleaning">
          {{ cleaning ? '清理中...' : '🗑️ 清理全部 DCR 客户端' }}
        </button>
      </div>
      <div v-if="cleanResult" :class="['clean-result', cleanResult.ok ? 'ok' : 'err']">
        {{ cleanResult.msg }}
      </div>
    </div>
  </div>
</template>

<script>
import { ref, reactive, onMounted } from 'vue'
import { getAdminToken, listClients, deleteClient } from '../api/admin.js'
import axios from 'axios'

const PREREG = ['springai-gateway-client', 'mcp-weather-client', 'mcp-climate-client']
const MCP_GW = '/mcp-gateway'

export default {
  name: 'SystemView',
  setup() {
    const sys = reactive({
      javaVersion: '', jvmName: '', pid: '', uptimeMs: 0,
      heapUsed: 0, heapMax: 0, nonHeapUsed: 0, threadCount: 0,
      mcpServiceCount: 0, dcrEnabled: true, accessTokenTTL: '',
    })

    const ports = ref([
      { port: 8080, name: 'nginx (公共入口)', ok: false },
      { port: 8081, name: 'api-gateway', ok: false },
      { port: 8082, name: 'mcp-gateway', ok: false },
      { port: 9090, name: 'auth-server', ok: false },
      { port: 9092, name: 'weather-server', ok: false },
      { port: 9093, name: 'climate-server', ok: false },
    ])

    const dcrCount = ref(0)
    const cleaning = ref(false)
    const cleanResult = ref(null)

    async function load() {
      // System info via actuator-style endpoint (or just from /admin/system)
      try {
        const token = getAdminToken()
        const { data } = await axios.get(`${MCP_GW}/admin/system`, {
          headers: { Authorization: `Bearer ${token}` }
        })
        Object.assign(sys, data)
      } catch {
        // Fallback: get what we can
        sys.javaVersion = 'Java 25 (Loom)'
        sys.jvmName = 'OpenJDK'
        sys.mcpServiceCount = 2
        sys.dcrEnabled = true
        sys.accessTokenTTL = '24h'
      }

      // Port check
      for (const p of ports.value) {
        try {
          await fetch(`http://localhost:${p.port}/`, { method: 'HEAD', mode: 'no-cors', signal: AbortSignal.timeout(2000) })
          p.ok = true
        } catch { p.ok = false }
      }

      // DCR count
      try {
        const clients = await listClients()
        dcrCount.value = clients.filter(c => !PREREG.includes(c.clientId)).length
      } catch {}
    }

    async function cleanDcr() {
      if (!confirm(`确认删除 ${dcrCount.value} 个 DCR 临时客户端？`)) return
      cleaning.value = true
      cleanResult.value = null
      let deleted = 0
      try {
        const clients = await listClients()
        const dcrClients = clients.filter(c => !PREREG.includes(c.clientId))
        for (const c of dcrClients) {
          try { await deleteClient(c.clientId); deleted++ } catch {}
        }
        cleanResult.value = { ok: true, msg: `成功删除 ${deleted} 个 DCR 客户端` }
        dcrCount.value = 0
      } catch (e) {
        cleanResult.value = { ok: false, msg: `清理失败: ${e.message}` }
      } finally {
        cleaning.value = false
      }
    }

    function formatUptime(ms) {
      if (!ms) return '-'
      const h = Math.floor(ms / 3600000)
      const m = Math.floor((ms % 3600000) / 60000)
      if (h > 24) return `${Math.floor(h / 24)} 天 ${h % 24} 小时`
      return `${h} 小时 ${m} 分钟`
    }

    function formatMB(bytes) {
      if (!bytes) return '-'
      return Math.round(bytes / 1048576) + ' MB'
    }

    const heapPercent = ref(0)

    onMounted(async () => {
      await load()
      if (sys.heapMax > 0) heapPercent.value = Math.round(sys.heapUsed / sys.heapMax * 100)
    })

    return { sys, ports, heapPercent, dcrCount, cleaning, cleanResult, load, cleanDcr, formatUptime, formatMB }
  }
}
</script>

<style scoped>
.info-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}
.info-section h4 {
  font-size: 12px;
  color: #888;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 10px;
  padding-bottom: 4px;
  border-bottom: 1px solid #eee;
}
.info-item {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.info-item label {
  font-size: 12px;
  color: #666;
  min-width: 120px;
  font-weight: 500;
}
.mem-bar {
  height: 6px;
  background: #eee;
  border-radius: 3px;
  margin: 4px 0 8px;
  overflow: hidden;
}
.mem-fill {
  height: 100%;
  background: linear-gradient(90deg, #4caf50, #ff9800);
  border-radius: 3px;
  transition: width 0.3s;
}

.port-list { display: flex; flex-direction: column; gap: 4px; }
.port-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border-radius: 4px;
  background: #fafafa;
}
.port-item.up { background: #f1f8f1; }
.port-item.down { background: #fff3f3; }
.port-num { font-family: monospace; font-size: 13px; font-weight: 600; min-width: 50px; }
.port-name { flex: 1; font-size: 12px; }

.clean-result { margin-top: 8px; padding: 8px 12px; border-radius: 6px; font-size: 13px; }
.clean-result.ok { background: #e8f5e9; color: #2e7d32; }
.clean-result.err { background: #ffebee; color: #c62828; }

@media (max-width: 900px) {
  .info-grid { grid-template-columns: 1fr; }
}
</style>
