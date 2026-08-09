<template>
  <div class="ak-view">
    <!-- Summary Cards -->
    <div class="summary">
      <div class="stat-card">
        <span class="stat-num">{{ keys.length }}</span>
        <span class="stat-label">API Key 总数</span>
      </div>
      <div class="stat-card">
        <span class="stat-num">{{ keys.filter(k => k.enabled).length }}</span>
        <span class="stat-label">启用中</span>
      </div>
      <div class="stat-card">
        <span class="stat-num">{{ keys.filter(k => !k.enabled).length }}</span>
        <span class="stat-label">已吊销</span>
      </div>
      <div class="stat-card">
        <span class="stat-num">{{ keys.filter(k => k.serviceScope === '*').length }}</span>
        <span class="stat-label">全局权限</span>
      </div>
    </div>

    <!-- Create Key Form -->
    <div class="card">
      <h3>➕ 创建 API Key</h3>
      <div class="create-form">
        <div class="form-row">
          <div class="form-group flex-1">
            <label>名称</label>
            <input v-model="newKey.name" placeholder="ci-cd-pipeline" class="input" />
          </div>
          <div class="form-group flex-1">
            <label>服务范围</label>
            <select v-model="newKey.serviceScope" class="input">
              <option value="*">全部服务 (*)</option>
              <option value="weather">仅 weather</option>
              <option value="climate">仅 climate</option>
              <option value="weather,climate">weather + climate</option>
              <option value="custom">自定义...</option>
            </select>
          </div>
          <div v-if="newKey.serviceScope === 'custom'" class="form-group flex-1">
            <label>自定义范围</label>
            <input v-model="customScope" placeholder="service1,service2" class="input" />
          </div>
        </div>
        <div class="form-row">
          <div class="form-group flex-1">
            <label>描述</label>
            <input v-model="newKey.description" placeholder="CI/CD 流水线专用" class="input" />
          </div>
          <div class="form-group">
            <label>过期时间 (留空=永不过期)</label>
            <input v-model="newKey.expiresAt" type="datetime-local" class="input" />
          </div>
        </div>
        <button class="btn-primary" @click="createKey" :disabled="!newKey.name || creating">
          {{ creating ? '创建中...' : '创建 API Key' }}
        </button>
      </div>
    </div>

    <!-- New Key Alert -->
    <div v-if="createdKey" class="card created-alert">
      <h3>🎉 API Key 创建成功</h3>
      <p class="alert-warn">⚠️ AccessKey Secret 只显示一次，请立即保存！</p>
      <div class="key-display">
        <div class="key-row">
          <span class="key-label">AccessKey ID:</span>
          <code class="mono key-value">{{ createdKey.accessKeyId }}</code>
          <button class="btn-copy" @click="copy(createdKey.accessKeyId)">📋</button>
        </div>
        <div class="key-row">
          <span class="key-label">AccessKey Secret:</span>
          <code class="mono key-value secret">{{ createdKey.accessKeySecret }}</code>
          <button class="btn-copy" @click="copy(createdKey.accessKeySecret)">📋</button>
        </div>
        <div class="key-row">
          <span class="key-label">完整连接串:</span>
          <code class="mono key-value">{{ createdKey.accessKeyId }}:{{ createdKey.accessKeySecret }}</code>
          <button class="btn-copy" @click="copy(createdKey.accessKeyId + ':' + createdKey.accessKeySecret)">📋</button>
        </div>
      </div>
      <div class="usage-example">
        <p class="key-label">使用示例:</p>
        <pre class="code-block"># Bearer 模式 (简单)
curl -X POST http://localhost:8080/mcp-gateway/weather/mcp \\
  -H "X-API-Key: {{ createdKey.accessKeyId }}:{{ createdKey.accessKeySecret }}" \\
  -H "Content-Type: application/json" \\
  -d '{"jsonrpc":"2.0","method":"initialize",...}'</pre>
      </div>
      <button class="btn-sm" @click="createdKey = null">关闭</button>
    </div>

    <!-- Key List Table -->
    <div class="card">
      <h3>🔑 API Key 列表</h3>
      <table v-if="keys.length">
        <thead>
          <tr>
            <th>名称</th>
            <th>AccessKey ID</th>
            <th>服务范围</th>
            <th>状态</th>
            <th>过期</th>
            <th>最后使用</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="key in keys" :key="key.id" :class="{ disabled: !key.enabled }">
            <td><strong>{{ key.name }}</strong></td>
            <td><code class="mono">{{ key.accessKeyId }}</code></td>
            <td>
              <span :class="['badge', key.serviceScope === '*' ? 'blue' : 'orange']">
                {{ key.serviceScope }}
              </span>
            </td>
            <td>
              <span :class="['badge', key.enabled ? 'green' : 'red']">
                {{ key.enabled ? '启用' : '已吊销' }}
              </span>
            </td>
            <td>
              <span :class="['badge', key.expiresAt === 'never' ? 'green' : 'orange']">
                {{ key.expiresAt === 'never' ? '永不过期' : key.expiresAt }}
              </span>
            </td>
            <td class="mono">{{ key.lastUsedAt ? formatTime(key.lastUsedAt) : '从未' }}</td>
            <td>
              <div class="actions">
                <button v-if="key.enabled" class="btn-sm danger" @click="revokeKey(key)">吊销</button>
                <button v-else class="btn-sm success" @click="enableKey(key)">启用</button>
                <button class="btn-sm danger" @click="deleteKey(key)">删除</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else class="empty">暂无 API Key</p>
    </div>
  </div>
</template>

<script>
import { ref, onMounted } from 'vue'
import { listApiKeys, createApiKey, revokeApiKey, enableApiKey, deleteApiKey } from '../api/admin.js'

export default {
  name: 'ApiKeysView',
  setup() {
    const keys = ref([])
    const creating = ref(false)
    const createdKey = ref(null)
    const customScope = ref('')

    const newKey = ref({
      name: '',
      serviceScope: '*',
      description: '',
      expiresAt: '',
    })

    async function refresh() {
      try { keys.value = await listApiKeys() } catch {}
    }

    async function createKey() {
      creating.value = true
      try {
        const scope = newKey.value.serviceScope === 'custom' ? customScope.value : newKey.value.serviceScope
        let expiresAt = null
        if (newKey.value.expiresAt) {
          expiresAt = new Date(newKey.value.expiresAt).toISOString()
        }
        createdKey.value = await createApiKey({
          name: newKey.value.name,
          serviceScope: scope,
          description: newKey.value.description,
          createdBy: 'admin-console',
          expiresAt,
        })
        newKey.value = { name: '', serviceScope: '*', description: '', expiresAt: '' }
        await refresh()
      } catch (err) {
        alert('创建失败: ' + (err.response?.data?.error || err.message))
      } finally {
        creating.value = false
      }
    }

    async function revokeKey(key) {
      if (!confirm(`确定吊销 API Key "${key.name}"？`)) return
      try { await revokeApiKey(key.id); await refresh() }
      catch (err) { alert('吊销失败: ' + err.message) }
    }

    async function enableKey(key) {
      try { await enableApiKey(key.id); await refresh() }
      catch (err) { alert('启用失败: ' + err.message) }
    }

    async function deleteKey(key) {
      if (!confirm(`⚠️ 永久删除 API Key "${key.name}"？此操作不可恢复！`)) return
      try { await deleteApiKey(key.id); await refresh() }
      catch (err) { alert('删除失败: ' + err.message) }
    }

    function copy(text) {
      navigator.clipboard.writeText(text)
    }

    function formatTime(iso) {
      if (!iso) return ''
      const d = new Date(iso)
      return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
    }

    onMounted(refresh)

    return { keys, newKey, creating, createdKey, customScope, createKey, revokeKey, enableKey, deleteKey, copy, formatTime }
  }
}
</script>

<style scoped>
.summary {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}
.stat-card {
  background: white;
  border-radius: 10px;
  padding: 16px;
  text-align: center;
  box-shadow: 0 1px 6px rgba(0,0,0,0.06);
}
.stat-num { display: block; font-size: 28px; font-weight: 700; color: #1a73e8; }
.stat-label { font-size: 12px; color: #888; }

.create-form { display: flex; flex-direction: column; gap: 12px; }
.form-row { display: flex; gap: 12px; }
.flex-1 { flex: 1; }
.input {
  width: 100%;
  padding: 8px 10px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 13px;
}
.input:focus { outline: none; border-color: #1a73e8; }

.created-alert { border: 2px solid #4caf50; background: #f1f8f1; }
.alert-warn { color: #e65100; font-weight: 600; margin: 8px 0; font-size: 13px; }
.key-display { display: flex; flex-direction: column; gap: 8px; margin: 12px 0; }
.key-row { display: flex; align-items: center; gap: 8px; }
.key-label { font-size: 12px; color: #666; min-width: 120px; font-weight: 500; }
.key-value {
  background: #f5f5f5;
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 12px;
  flex: 1;
  overflow-x: auto;
}
.key-value.secret { color: #c62828; background: #fff3f3; }
.btn-copy {
  background: none;
  border: 1px solid #ddd;
  border-radius: 4px;
  cursor: pointer;
  padding: 2px 6px;
  font-size: 14px;
}
.btn-copy:hover { background: #f0f0f0; }
.code-block {
  background: #1d1d1f;
  color: #e0e0e0;
  padding: 12px;
  border-radius: 8px;
  font-size: 12px;
  overflow-x: auto;
  margin-top: 8px;
}

.disabled td { opacity: 0.5; }
.actions { display: flex; gap: 6px; }
.empty { text-align: center; color: #999; padding: 20px; }
</style>
</template>
