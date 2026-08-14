<template>
  <div>
    <div class="card">
      <h3>📋 OAuth2 客户端管理</h3>

      <!-- Filters -->
      <div style="display:flex;gap:8px;margin-bottom:12px;flex-wrap:wrap;">
        <input v-model="search" placeholder="搜索 ID / 名称..." class="input-field" style="flex:1;min-width:200px" />
        <select v-model="filterType" class="input-field" style="width:130px">
          <option value="all">全部类型</option>
          <option value="public">公开 (PKCE)</option>
          <option value="confidential">机密</option>
        </select>
        <select v-model="filterSource" class="input-field" style="width:130px">
          <option value="all">全部来源</option>
          <option value="prereg">预注册</option>
          <option value="dcr">DCR 动态注册</option>
        </select>
      </div>

      <!-- Create Form (collapsible) -->
      <div class="create-toggle">
        <button class="btn-sm" @click="showCreate = !showCreate">
          {{ showCreate ? '收起' : '➕ 创建客户端' }}
        </button>
      </div>
      <div v-if="showCreate" class="create-form" style="margin-bottom:16px;padding:12px;background:#f8f9ff;border-radius:8px;">
        <div style="display:flex;gap:8px;flex-wrap:wrap;">
          <input v-model="newClient.clientId" placeholder="客户端ID" class="input-field" style="flex:1;min-width:150px" />
          <input v-model="newClient.clientName" placeholder="客户端名称" class="input-field" style="flex:1;min-width:150px" />
          <select v-model="newClient.clientType" class="input-field" style="width:140px">
            <option value="public">公开客户端(PKCE)</option>
            <option value="confidential">机密客户端</option>
          </select>
          <button class="btn-sm success" @click="createClient" :disabled="!newClient.clientId || !newClient.clientName">创建</button>
        </div>
        <div v-if="newClient.clientType === 'confidential'" style="display:flex;gap:8px;margin-top:8px;">
          <input v-model="newClient.clientSecret" placeholder="客户端密钥(可选,自动生成)" type="password" class="input-field" style="flex:1" />
        </div>
      </div>

      <!-- Summary -->
      <div style="display:flex;gap:12px;margin-bottom:12px;font-size:12px;color:#888;">
        <span>共 <strong>{{ filteredClients.length }}</strong> 个</span>
        <span>公开 <strong>{{ filteredClients.filter(c => !c.hasSecret).length }}</strong></span>
        <span>机密 <strong>{{ filteredClients.filter(c => c.hasSecret).length }}</strong></span>
      </div>

      <!-- Table -->
      <table v-if="filteredClients.length">
        <thead><tr>
          <th style="width:24px"></th>
          <th>客户端ID</th>
          <th>名称</th>
          <th>类型</th>
          <th>来源</th>
          <th>Scope</th>
          <th>操作</th>
        </tr></thead>
        <tbody>
          <template v-for="c in filteredClients" :key="c.clientId">
            <tr :class="{ 'detail-open': detailClientId === c.clientId }" @click="toggleDetail(c)">
              <td style="text-align:center;">
                <span :class="['expand-icon', { rotated: detailClientId === c.clientId }]">▶</span>
              </td>
              <td class="mono" style="max-width:180px;overflow:hidden;text-overflow:ellipsis;" :title="c.clientId">{{ c.clientId }}</td>
              <td>{{ c.clientName }}</td>
              <td>
                <span :class="['badge', c.hasSecret ? 'blue' : 'green']">{{ c.hasSecret ? '机密' : '公开' }}</span>
              </td>
              <td>
                <span :class="['badge', isPreregistered(c.clientId) ? 'orange' : 'gray']">
                  {{ isPreregistered(c.clientId) ? '预注册' : 'DCR' }}
                </span>
              </td>
              <td style="font-size:11px;">{{ formatScopes(c.scopes) }}</td>
              <td @click.stop>
                <button
                  v-if="!isPreregistered(c.clientId)"
                  class="btn-sm danger"
                  @click="del(c)"
                >删除</button>
                <span v-else class="badge gray">受保护</span>
              </td>
            </tr>
            <!-- Detail Row -->
            <tr v-if="detailClientId === c.clientId" class="detail-row">
              <td colspan="7">
                <div class="detail-panel">
                  <div class="detail-grid">
                    <div class="detail-section">
                      <h4>基本信息</h4>
                      <div class="detail-item"><label>客户端ID</label><code class="mono">{{ c.clientId }}</code></div>
                      <div class="detail-item"><label>名称</label><span>{{ c.clientName }}</span></div>
                      <div class="detail-item"><label>类型</label><span :class="['badge', c.hasSecret ? 'blue' : 'green']">{{ c.hasSecret ? '机密客户端' : '公开客户端 (PKCE)' }}</span></div>
                      <div class="detail-item"><label>密钥</label><span>{{ c.hasSecret ? '●●●●● (bcrypt)' : '无 (公开客户端)' }}</span></div>
                      <div class="detail-item" v-if="c.clientIdIssuedAt"><label>创建时间</label><span>{{ formatTime(c.clientIdIssuedAt) }}</span></div>
                      <div class="detail-item" v-if="c.id"><label>数据库ID</label><code class="mono">{{ c.id }}</code></div>
                    </div>
                    <div class="detail-section">
                      <h4>认证 & 授权</h4>
                      <div class="detail-item">
                        <label>认证方式</label>
                        <div class="tag-list">
                          <span v-for="m in parseJson(c.clientAuthenticationMethods)" :key="m" class="badge blue">{{ m }}</span>
                        </div>
                      </div>
                      <div class="detail-item">
                        <label>授权类型</label>
                        <div class="tag-list">
                          <span v-for="g in parseJson(c.authorizationGrantTypes)" :key="g"
                                :class="['badge', g === 'client_credentials' ? 'orange' : 'blue']">{{ g }}</span>
                        </div>
                      </div>
                      <div class="detail-item">
                        <label>Scope</label>
                        <div class="tag-list">
                          <span v-for="s in parseJson(c.scopes)" :key="s"
                                :class="['badge', s.startsWith('mcp:') ? 'green' : 'gray']">{{ s }}</span>
                        </div>
                      </div>
                    </div>
                    <div class="detail-section">
                      <h4>回调地址</h4>
                      <div class="redirect-list">
                        <div v-for="uri in parseJson(c.redirectUris)" :key="uri" class="redirect-item">
                          <code class="mono">{{ uri }}</code>
                          <span v-if="isLocalCallback(uri)" class="badge gray">本地</span>
                          <span v-else-if="isClaudeCallback(uri)" class="badge blue">Claude</span>
                          <span v-else class="badge orange">外部</span>
                        </div>
                        <div v-if="!parseJson(c.redirectUris).length" class="empty-hint">未配置</div>
                      </div>
                    </div>
                  </div>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
      <p v-else style="color:#999;padding:12px;">无匹配客户端</p>
    </div>
  </div>
</template>

<script>
import { ref, computed, onMounted } from 'vue'
import { listClients, createClient as apiCreate, deleteClient as apiDelete } from '../api/admin.js'

// PREREG_CLIENTS is now dynamic — derived from client.source === 'PRE-REGISTERED'

export default {
  setup() {
    const clients = ref([])
    const search = ref('')
    const filterType = ref('all')
    const filterSource = ref('all')
    const detailClientId = ref(null)
    const showCreate = ref(false)
    const newClient = ref({ clientId: '', clientName: '', clientType: 'public', clientSecret: '' })

    const filteredClients = computed(() => {
      return clients.value.filter(c => {
        if (search.value && !c.clientId.includes(search.value) && !c.clientName.includes(search.value)) return false
        if (filterType.value === 'public' && c.hasSecret) return false
        if (filterType.value === 'confidential' && !c.hasSecret) return false
        if (filterSource.value === 'prereg' && !isPreregistered(c.clientId)) return false
        if (filterSource.value === 'dcr' && isPreregistered(c.clientId)) return false
        return true
      })
    })

    async function load() {
      try { clients.value = await listClients() } catch {}
    }

    function toggleDetail(c) {
      detailClientId.value = detailClientId.value === c.clientId ? null : c.clientId
    }

    async function createClient() {
      try {
        const body = {
          clientId: newClient.value.clientId,
          clientName: newClient.value.clientName,
          isPublic: newClient.value.clientType === 'public',
          redirectUris: '["http://localhost:19876/callback"]',
          scopes: '["mcp:read","mcp:write"]',
        }
        if (newClient.value.clientSecret) body.clientSecret = newClient.value.clientSecret
        await apiCreate(body)
        newClient.value = { clientId: '', clientName: '', clientType: 'public', clientSecret: '' }
        showCreate.value = false
        await load()
      } catch {}
    }

    async function del(c) {
      if (!confirm(`确认删除客户端 ${c.clientId}？`)) return
      try { await apiDelete(c.clientId); await load() } catch {}
    }

    function isPreregistered(c) {
      // Accept client object or string id
      const client = typeof c === 'string' ? clients.value.find(cl => cl.clientId === c) : c
      return client?.source === 'PRE-REGISTERED'
    }
    function isLocalCallback(uri) { return uri.includes('localhost') || uri.includes('127.0.0.1') }
    function isClaudeCallback(uri) { return uri.includes('claude.ai') }
    function parseJson(str) {
      if (!str) return []
      try { return JSON.parse(str) } catch { return [str] }
    }
    function formatScopes(str) {
      const arr = parseJson(str)
      if (arr.length <= 2) return arr.join(', ')
      return arr.slice(0, 2).join(', ') + ` +${arr.length - 2}`
    }
    function formatTime(t) {
      if (!t) return '-'
      return new Date(t).toLocaleString('zh-CN')
    }

    onMounted(load)
    return {
      clients, filteredClients, search, filterType, filterSource,
      detailClientId, showCreate, newClient,
      toggleDetail, createClient, del,
      isPreregistered, isLocalCallback, isClaudeCallback,
      parseJson, formatScopes, formatTime
    }
  }
}
</script>

<style scoped>
.input-field {
  padding: 8px 10px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 13px;
}
.input-field:focus { outline: none; border-color: #1a73e8; }
.create-toggle { margin-bottom: 8px; }

.expand-icon {
  display: inline-block;
  font-size: 10px;
  color: #888;
  transition: transform 0.2s;
}
.expand-icon.rotated { transform: rotate(90deg); }

.detail-open { background: #f8f9ff !important; }
.detail-row td { padding: 0 !important; border-bottom: 2px solid #1a73e8 !important; }

.detail-panel {
  background: #f8f9ff;
  padding: 16px 20px;
  animation: slideDown 0.15s ease;
}
@keyframes slideDown { from { opacity: 0; max-height: 0; } to { opacity: 1; max-height: 400px; } }

.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 20px;
}
.detail-section h4 {
  font-size: 12px;
  color: #888;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 10px;
  padding-bottom: 4px;
  border-bottom: 1px solid #e0e0e0;
}
.detail-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 8px;
}
.detail-item label {
  font-size: 11px;
  color: #888;
  min-width: 70px;
  font-weight: 500;
  padding-top: 2px;
}
.tag-list { display: flex; gap: 4px; flex-wrap: wrap; }
.redirect-list { display: flex; flex-direction: column; gap: 6px; }
.redirect-item {
  display: flex;
  align-items: center;
  gap: 6px;
  background: white;
  padding: 4px 8px;
  border-radius: 4px;
}
.empty-hint { color: #999; font-size: 12px; font-style: italic; }

@media (max-width: 900px) {
  .detail-grid { grid-template-columns: 1fr; }
}
</style>
