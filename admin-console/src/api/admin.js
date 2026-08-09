import axios from 'axios'

// ─── Config ──────────────────────────────────────────────
// API 调用走 nginx 已有的 /mcp-gateway/ → 8082 路由
// 页面走 api-gateway 白名单 /ecso/admin/ → 9094
const MCP_GATEWAY = '/mcp-gateway'
const AUTH_SERVER = '/api-gateway/ecso/auth'

// Admin token — localStorage
let adminToken = localStorage.getItem('admin_token') || ''

export function setAdminToken(token) {
  adminToken = token
  localStorage.setItem('admin_token', token)
}

export function getAdminToken() {
  return adminToken
}

export function clearAdminToken() {
  adminToken = ''
  localStorage.removeItem('admin_token')
}

// ─── Axios Instance ──────────────────────────────────────
const api = axios.create()

api.interceptors.request.use(config => {
  if (adminToken) {
    config.headers.Authorization = `Bearer ${adminToken}`
  }
  return config
})

api.interceptors.response.use(
  res => res,
  err => {
    if (err.response?.status === 401) {
      clearAdminToken()
    }
    return Promise.reject(err)
  }
)

// ─── Login (sys_user) ────────────────────────────────────

export async function login(username, password) {
  const { data } = await api.post(`${MCP_GATEWAY}/admin/login`, { username, password })
  if (data.adminToken) {
    setAdminToken(data.adminToken)
  }
  return data
}

// ─── User CRUD ───────────────────────────────────────────

export async function listUsers() {
  const { data } = await api.get(`${MCP_GATEWAY}/admin/users`)
  return data
}

export async function createUser({ username, password }) {
  const { data } = await api.post(`${MCP_GATEWAY}/admin/users`, { username, password })
  return data
}

export async function updateUser(id, body) {
  const { data } = await api.put(`${MCP_GATEWAY}/admin/users/${id}`, body)
  return data
}

export async function deleteUser(id) {
  const { data } = await api.delete(`${MCP_GATEWAY}/admin/users/${id}`)
  return data
}

// ─── OAuth Client CRUD ───────────────────────────────────

export async function listClients() {
  const { data } = await api.get(`${MCP_GATEWAY}/admin/clients`)
  return data
}

export async function createClient(body) {
  const { data } = await api.post(`${MCP_GATEWAY}/admin/clients`, body)
  return data
}

export async function deleteClient(clientId) {
  const { data } = await api.delete(`${MCP_GATEWAY}/admin/clients/${clientId}`)
  return data
}

// ─── API Key CRUD ────────────────────────────────────────

export async function listApiKeys() {
  const { data } = await api.get(`${MCP_GATEWAY}/admin/api-keys`)
  return data
}

export async function createApiKey({ name, serviceScope, description, createdBy, expiresAt }) {
  const { data } = await api.post(`${MCP_GATEWAY}/admin/api-keys`, {
    name, serviceScope, description, createdBy, expiresAt
  })
  return data
}

export async function revokeApiKey(id) {
  const { data } = await api.put(`${MCP_GATEWAY}/admin/api-keys/${id}/revoke`)
  return data
}

export async function enableApiKey(id) {
  const { data } = await api.put(`${MCP_GATEWAY}/admin/api-keys/${id}/enable`)
  return data
}

export async function deleteApiKey(id) {
  const { data } = await api.delete(`${MCP_GATEWAY}/admin/api-keys/${id}`)
  return data
}

// ─── OAuth2 Info ─────────────────────────────────────────

export async function getAuthServerMetadata() {
  const { data } = await axios.get(`${AUTH_SERVER}/.well-known/oauth-authorization-server`)
  return data
}

export async function getProtectedResourceMetadata(service) {
  const { data } = await axios.get(`${MCP_GATEWAY}/${service}/.well-known/oauth-protected-resource`)
  return data
}

// ─── MCP Service Status ──────────────────────────────────

export async function checkMcpService(service) {
  try {
    const { status } = await axios.post(
      `${MCP_GATEWAY}/${service}/mcp`,
      { jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'admin-console', version: '1.0' } } },
      { headers: { 'Content-Type': 'application/json' } }
    )
    return status === 200 || status === 401
  } catch {
    return false
  }
}
