<template>
  <div>
    <div class="card">
      <h3>👤 用户管理</h3>
      <div style="display:flex;gap:8px;margin-bottom:16px;">
        <input v-model="newUser.username" placeholder="用户名" class="input-field" style="flex:1" />
        <input v-model="newUser.password" placeholder="密码" type="password" class="input-field" style="flex:1" />
        <button class="btn-sm success" @click="createUser" :disabled="!newUser.username || !newUser.password">创建</button>
      </div>
      <table v-if="users.length">
        <thead><tr><th>ID</th><th>用户名</th><th>状态</th><th>创建时间</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="u in users" :key="u.id">
            <td class="mono">{{ u.id }}</td>
            <td><strong>{{ u.username }}</strong></td>
            <td>
              <span :class="['badge', u.enabled ? 'green' : 'red']">{{ u.enabled ? '启用' : '禁用' }}</span>
            </td>
            <td>{{ formatTime(u.createdAt) }}</td>
            <td>
              <button v-if="u.username !== 'admin'" class="btn-sm danger" @click="del(u)">删除</button>
              <span v-else class="badge gray">系统</span>
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
import { listUsers, createUser as apiCreate, deleteUser as apiDelete } from '../api/admin.js'

export default {
  setup() {
    const users = ref([])
    const newUser = ref({ username: '', password: '' })

    async function load() {
      try { users.value = await listUsers() } catch {}
    }

    async function createUser() {
      try {
        await apiCreate(newUser.value)
        newUser.value = { username: '', password: '' }
        await load()
      } catch {}
    }

    async function del(u) {
      if (!confirm(`确认删除用户 ${u.username}？`)) return
      try { await apiDelete(u.id); await load() } catch {}
    }

    function formatTime(t) {
      if (!t) return '-'
      return new Date(t).toLocaleString('zh-CN')
    }

    onMounted(load)
    return { users, newUser, createUser, del, formatTime }
  }
}
</script>
