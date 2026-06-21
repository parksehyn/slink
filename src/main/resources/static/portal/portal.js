/* Shared utilities for SOLID Service Portal */

const KEY_STORAGE = 'slink_api_key';
const KEY_EMAIL   = 'slink_email';

function getApiKey() { return localStorage.getItem(KEY_STORAGE); }
function getEmail()  { return localStorage.getItem(KEY_EMAIL); }

// Relay API base URL. 비우면 상대경로(포털을 서빙한 서버와 동일 origin).
// 노트북에서 포털을 따로 서빙할 때 VM의 Relay 주소로 지정한다.
// ?api=http://10.0.10.20:8081 쿼리로도 설정 가능(localStorage에 저장됨).
const KEY_API_BASE = 'slink_api_base';
function getApiBase() {
    const q = new URLSearchParams(location.search).get('api');
    if (q !== null) localStorage.setItem(KEY_API_BASE, q.replace(/\/$/, ''));
    return localStorage.getItem(KEY_API_BASE) || '';
}
function setApiBase(v) {
    v = (v || '').trim().replace(/\/$/, '');
    if (v) localStorage.setItem(KEY_API_BASE, v);
    else localStorage.removeItem(KEY_API_BASE);
}
function apiUrl(path) { return getApiBase() + path; }

function setAuth(apiKey, email) {
    localStorage.setItem(KEY_STORAGE, apiKey);
    localStorage.setItem(KEY_EMAIL, email);
}

function clearAuth() {
    localStorage.removeItem(KEY_STORAGE);
    localStorage.removeItem(KEY_EMAIL);
}

async function apiFetch(path, options = {}) {
    const key = getApiKey();
    const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
    if (key) headers['Authorization'] = 'Bearer ' + key;
    const res = await fetch(apiUrl(path), { ...options, headers });
    if (res.status === 401) { clearAuth(); location.href = '/portal/index.html'; }
    return res;
}

function formatExpiry(iso) {
    if (!iso) return '—';
    const diff = new Date(iso) - Date.now();
    if (diff <= 0) return '만료됨';
    const h = Math.floor(diff / 3_600_000);
    const m = Math.floor((diff % 3_600_000) / 60_000);
    return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

function scopeBadge(scope) {
    const map = {
        PRIVATE:  ['#6b7280', '비공개'],
        INTERNAL: ['#2563eb', '내부망'],
        TEAM:     ['#7c3aed', '팀'],
        PUBLIC:   ['#16a34a', '공개'],
    };
    const [color, label] = map[scope] || ['#6b7280', scope];
    return `<span style="background:${color};color:#fff;padding:2px 8px;border-radius:4px;font-size:12px">${label}</span>`;
}

function statusBadge(status) {
    const map = {
        UNKNOWN: ['#9ca3af', '—'],
        ONLINE:  ['#16a34a', '온라인'],
        OFFLINE: ['#dc2626', '오프라인'],
        PENDING: ['#d97706', '대기 중'],
    };
    const [color, label] = map[status] || ['#9ca3af', status];
    return `<span style="color:${color};font-weight:600">${label}</span>`;
}

async function requireAuth() {
    if (!getApiKey()) { location.href = '/portal/index.html'; return false; }
    const res = await apiFetch('/api/users/me');
    if (!res.ok) { clearAuth(); location.href = '/portal/index.html'; return false; }
    return true;
}

function dnsTypeBadge(type) {
    return `<span class="type-badge">${type}</span>`;
}

function connTypeBadge(type) {
    const map = {
        JUPYTER: '#7c3aed',
        HTTP:    '#2563eb',
        SSH:     '#0f766e',
        OTHER:   '#6b7280',
    };
    const color = map[type] || '#6b7280';
    return `<span style="background:${color};color:#fff;padding:2px 8px;border-radius:4px;font-size:12px">${type}</span>`;
}

function accessPolicyLabel(policy) {
    const map = {
        DKU_INTERNAL: '단국대 내부 전용',
        ALLOWLIST:    '허용 대상 지정',
    };
    return map[policy] || policy || '단국대 내부 전용';
}

function escapeHtml(s) {
    return String(s == null ? '' : s)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

// 현재 사용자의 SOLID VM 목록 (CloudStackProvider, 현재 모의). 캐시 후 재사용.
let _vmCache = null;
async function fetchVms() {
    if (_vmCache) return _vmCache;
    try {
        const res = await apiFetch('/api/vms');
        if (!res.ok) return [];
        _vmCache = await res.json();
        return _vmCache;
    } catch (e) {
        return [];
    }
}
