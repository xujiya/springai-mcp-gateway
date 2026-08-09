<template>
  <div>
    <div class="card">
      <h3>📋 OAuth2 客户端管理</h3>
      <div class="create-form" style="margin-bottom:16px;">
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

      <table v-if="clients.length">
        <thead><tr><th>客户端ID</th><th>名称</th><th>类型</th><th>授权方式</th><th>Scope</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="c in clients" :key="c.clientId">
            <td class="mono" style="max-width:200px;overflow:hidden;text-overflow:ellipsis;">{{ c.clientId }}</td>
            <td>{{ c.clientName }}</td>
            <td>
              <span :class="['badge', c.hasSecret ? 'blue' : 'green']">{{ c.hasSecret ? '机密' : '公开' }}</span>
            </td>
            <td style="font-size:11px;">{{ c.clientAuthenticationMethods }}</td>
            <td style="font-size:11px;">{{ c.scopes }}</td>
            <td>
              <button
                v-if="!isProtectedClient(c.clientId)"
                class="btn-sm danger"
                @click="del(c)"
              >删除</button>
              <span v-else class="badge gray">预注册</span>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-else style="color:#999">加载中...</p>
    </div>
  </div>
</template>

<script>
import { ref, onMounted } from 'vue'
import { listClients, createClient as apiCreate, deleteClient as apiDelete } from '../api/admin.js'

const PROTECTED_CLIENTS = ['springai-gateway-client', 'mcp-weather-client', 'mcp-climate-client']

export default {
  setup() {
    const clients = ref([])
    const newClient = ref({ clientId: '', clientName: '', clientType: 'public', clientSecret: '' })

    async function load() {
      try { clients.value = await listClients() } catch {}
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
        if (newClient.value.clientSecret) {
          body.clientSecret = newClient.value.clientSecret
        }
        await apiCreate(body)
        newClient.value = { clientId: '', clientName: '', clientType: 'public', clientSecret: '' }
        await load()
      } catch {}
    }

    async function del(c) {
      if (!confirm(`确认删除客户端 ${c.clientId}？`)) return
      try { await apiDelete(c.clientId); await load() } catch {}
    }

    function isProtectedClient(id) {
      return PROTECTED_CLIENTS.includes(id)
    }

    onMounted(load)
    return { clients, newClient, createClient, del, isProtectedClient }
  }
}
</script>
