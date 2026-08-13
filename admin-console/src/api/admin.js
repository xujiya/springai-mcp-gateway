import axios from "axios";

// ─── Config ──────────────────────────────────────────────
// 所有管理 API 走 API Gateway → mcp-gateway (统一 Bearer adm- token)
// 公开端点走 HAProxy /mcp-gateway (PRM) + API Gateway /ecso/auth (AS metadata)
const ADMIN_API = "/api-gateway/ecso/admin/mcp";
const PUBLIC_MCP = "/mcp-gateway";
const PUBLIC_AUTH = "/api-gateway/ecso/auth";

// Admin token — localStorage
let adminToken = localStorage.getItem("admin_token") || "";

export function setAdminToken(token) {
	adminToken = token;
	localStorage.setItem("admin_token", token);
}

export function getAdminToken() {
	return adminToken;
}

export function clearAdminToken() {
	adminToken = "";
	localStorage.removeItem("admin_token");
}

// ─── Axios Instance (Bearer adm- token) ──────────────────
const api = axios.create();

api.interceptors.request.use((config) => {
	if (adminToken) {
		config.headers.Authorization = `Bearer ${adminToken}`;
	}
	return config;
});

api.interceptors.response.use(
	(res) => res,
	(err) => {
		if (err.response?.status === 401) {
			clearAdminToken();
		}
		return Promise.reject(err);
	},
);

// ─── Login ───────────────────────────────────────────────

export async function login(username, password) {
	const { data } = await api.post(`${ADMIN_API}/admin/login`, {
		username,
		password,
	});
	if (data.adminToken) {
		setAdminToken(data.adminToken);
	}
	return data;
}

// ─── User CRUD ───────────────────────────────────────────

export async function listUsers() {
	const { data } = await api.get(`${ADMIN_API}/admin/users`);
	return data;
}

export async function createUser({ username, password, roles }) {
	const { data } = await api.post(`${ADMIN_API}/admin/users`, {
		username,
		password,
		roles,
	});
	return data;
}

export async function updateUser(id, body) {
	const { data } = await api.put(`${ADMIN_API}/admin/users/${id}`, body);
	return data;
}

export async function deleteUser(id) {
	const { data } = await api.delete(`${ADMIN_API}/admin/users/${id}`);
	return data;
}

// ─── OAuth Client 列表 (只读, mcp-gateway) ─────────────

export async function listClients() {
	const { data } = await api.get(`${ADMIN_API}/admin/clients`);
	return data;
}

export async function createClient(/* body */) {
	throw new Error("Client registration 请用 DCR 端点");
}

export async function deleteClient(/* clientId */) {
	throw new Error("Client deletion 请用 DCR 端点");
}

// ─── API Key CRUD ────────────────────────────────────────

export async function listApiKeys() {
	const { data } = await api.get(`${ADMIN_API}/admin/api-keys`);
	return data;
}

export async function createApiKey({
	name,
	serviceScope,
	description,
	createdBy,
	expiresAt,
}) {
	const { data } = await api.post(`${ADMIN_API}/admin/api-keys`, {
		name,
		serviceScope,
		description,
		createdBy,
		expiresAt,
	});
	return data;
}

export async function revokeApiKey(id) {
	const { data } = await api.put(`${ADMIN_API}/admin/api-keys/${id}/revoke`);
	return data;
}

export async function enableApiKey(id) {
	const { data } = await api.put(`${ADMIN_API}/admin/api-keys/${id}/enable`);
	return data;
}

export async function deleteApiKey(id) {
	const { data } = await api.delete(`${ADMIN_API}/admin/api-keys/${id}`);
	return data;
}

// ─── OAuth2 Info (public) ────────────────────────────────

export async function getAuthServerMetadata() {
	const { data } = await axios.get(
		`${PUBLIC_AUTH}/.well-known/oauth-authorization-server`,
	);
	return data;
}

export async function getProtectedResourceMetadata(service) {
	const { data } = await axios.get(
		`${PUBLIC_MCP}/${service}/.well-known/oauth-protected-resource`,
	);
	return data;
}

// ─── MCP Service Status ──────────────────────────────────

export async function checkMcpService(service) {
	try {
		const { status } = await axios.get(
			`${PUBLIC_MCP}/${service}/.well-known/oauth-protected-resource`,
		);
		return status === 200;
	} catch {
		return false;
	}
}
