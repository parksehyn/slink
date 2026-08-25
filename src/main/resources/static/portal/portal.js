/* Shared utilities for SOLID Service Portal */

const KEY_STORAGE = 'slink_token';   // SOLID 세션 토큰(slk-…)
const KEY_EMAIL   = 'slink_account'; // 표시용 계정(학번)

function getApiKey() { return localStorage.getItem(KEY_STORAGE); }  // 세션 토큰 반환
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
    _vmCache = null;   // 새 로그인 → VM 목록 캐시 무효화(이전 세션/계정 잔존 방지)
}

function clearAuth() {
    localStorage.removeItem(KEY_STORAGE);
    localStorage.removeItem(KEY_EMAIL);
    _vmCache = null;
}

async function apiFetch(path, options = {}) {
    const key = getApiKey();
    const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
    if (key) headers['Authorization'] = 'Bearer ' + key;
    // 401 자동 로그아웃은 하지 않는다(탭별 엔드포인트 상태가 달라 오작동 방지). 호출부가 처리한다.
    return fetch(apiUrl(path), { ...options, headers });
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
    const res = await apiFetch('/api/auth/me');
    if (!res.ok) { clearAuth(); location.href = '/portal/index.html'; return false; }
    return true;
}

function dnsTypeBadge(type) {
    return `<span class="type-badge">${type}</span>`;
}

function dnsStatusBadge(status) {
    const map = {
        PENDING_SYNC: ['#d97706', '반영 대기'],
        ACTIVE:       ['#16a34a', '활성'],
        FAILED:       ['#dc2626', '실패'],
        DELETED:      ['#9ca3af', '삭제됨'],
    };
    const [color, label] = map[status] || ['#9ca3af', status || '—'];
    return `<span style="color:${color};font-weight:600">${label}</span>`;
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
