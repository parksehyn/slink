/* Shared utilities for SOLID Service Portal */

const KEY_STORAGE = 'slink_api_key';
const KEY_EMAIL   = 'slink_email';

function getApiKey() { return localStorage.getItem(KEY_STORAGE); }
function getEmail()  { return localStorage.getItem(KEY_EMAIL); }

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
    const res = await fetch(path, { ...options, headers });
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
