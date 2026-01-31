const tabs = document.querySelectorAll('.nav');
const sections = {
  overview: document.getElementById('tab-overview'),
  users: document.getElementById('tab-users'),
  projects: document.getElementById('tab-projects'),
  health: document.getElementById('tab-health'),
  tests: document.getElementById('tab-tests'),
  logs: document.getElementById('tab-logs'),
};

tabs.forEach(b => b.addEventListener('click', () => {
  tabs.forEach(x => x.classList.remove('active'));
  b.classList.add('active');
  Object.values(sections).forEach(s => s.classList.remove('show'));
  sections[b.dataset.tab].classList.add('show');
}));

function badge(state){
  const s = (state||'').toUpperCase();
  if (s === 'UP') return `<span class="badge up">UP</span>`;
  if (s === 'DOWN') return `<span class="badge down">DOWN</span>`;
  if (s === 'INACTIVE') return `<span class="badge down">INACTIVE</span>`;
  return `<span class="badge warn">${s || 'UNKNOWN'}</span>`;
}

async function api(url, opts){
  const res = await fetch(url, opts);
  if (res.status === 401) location.href = '/admin/login.html';
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || 'Request failed');
  return data;
}

function fillSimBars(){
  const el = document.getElementById('simBars');
  el.innerHTML = '';
  for (let i=0;i<36;i++){
    const h = 20 + Math.floor(Math.random()*100);
    const s = document.createElement('span');
    s.style.height = h + 'px';
    el.appendChild(s);
  }
}
fillSimBars();
setInterval(fillSimBars, 2500);

async function loadOverview(){
  const sys = await api('/api/admin/system');
  document.getElementById('sysHost').textContent = sys.host;
  document.getElementById('sysPort').textContent = sys.port;
  document.getElementById('sysUptime').textContent = sys.uptime;

  const users = await api('/api/admin/users');
  document.getElementById('kpiUsers').textContent = users.length;
  document.getElementById('kpiUsersSub').textContent = 'users in creds.json';

  const projs = await api('/api/admin/projects');
  document.getElementById('kpiProjects').textContent = projs.length;
  const mis = projs.filter(p => p.status === 'MISCONFIGURED').length;
  const ina = projs.filter(p => p.status === 'INACTIVE').length;
  document.getElementById('kpiProjectsSub').textContent = `misconfigured:${mis} | inactive:${ina}`;
}

async function loadProjects(){
  const rows = await api('/api/admin/projects');
  const tb = document.querySelector('#projectsTbl tbody');
  tb.innerHTML = '';
  rows.forEach(p => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td>${p.name}</td>
      <td>${p.path || '-'}</td>
      <td>${p.uiPresent ? 'YES' : 'NO'}</td>
      <td>${badge(p.status)}</td>
      <td>${p.message || ''}</td>
    `;
    tb.appendChild(tr);
  });
}

async function loadHealth(){
  const rows = await api('/api/admin/health');
  const tb = document.querySelector('#healthTbl tbody');
  tb.innerHTML = '';
  const upCount = rows.filter(r => r.state === 'UP').length;
  document.getElementById('kpiUp').textContent = upCount;
  document.getElementById('kpiUpSub').textContent = `of ${rows.length}`;

  rows.forEach(r => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td>${r.name}</td>
      <td>${r.path || '-'}</td>
      <td>${badge(r.state)}</td>
      <td>${r.http || '-'}</td>
      <td>${r.ms ?? '-'}</td>
      <td>${r.message || ''}</td>
    `;
    tb.appendChild(tr);
  });
}

async function loadTests(){
  const rows = await api('/api/admin/tests');
  const tb = document.querySelector('#testsTbl tbody');
  tb.innerHTML = '';
  rows.forEach(t => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td>${t.project}</td>
      <td>${t.test}</td>
      <td>${badge(t.result)}</td>
      <td>${t.message || ''}</td>
      <td>${t.time || ''}</td>
    `;
    tb.appendChild(tr);
  });
}

async function loadUsers(){
  const rows = await api('/api/admin/users');
  const tb = document.querySelector('#usersTbl tbody');
  tb.innerHTML = '';

  rows.forEach(u => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td>${u.userId}</td>
      <td contenteditable="true" data-k="name">${u.name||''}</td>
      <td contenteditable="true" data-k="email">${u.email||''}</td>
      <td>${u.createdAt||''}</td>
      <td style="white-space:nowrap">
        <button class="btn-sm" data-act="save">Save</button>
        <button class="btn-sm danger" data-act="del">Del</button>
      </td>
    `;
    tr.querySelector('[data-act="save"]').onclick = async () => {
      const name = tr.querySelector('[data-k="name"]').textContent.trim();
      const email = tr.querySelector('[data-k="email"]').textContent.trim();
      await api('/api/admin/users', {
        method:'PUT',
        headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},
        body: new URLSearchParams({ userId:u.userId, name, email }).toString()
      });
      await loadUsers();
      await loadOverview();
    };
    tr.querySelector('[data-act="del"]').onclick = async () => {
      if (!confirm('Delete user ' + u.userId + '?')) return;
      await api('/api/admin/users', {
        method:'DELETE',
        headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},
        body: new URLSearchParams({ userId:u.userId }).toString()
      });
      await loadUsers();
      await loadOverview();
    };
    tb.appendChild(tr);
  });
}

document.getElementById('btnAddUser')?.addEventListener('click', async () => {
  const name = document.getElementById('u_name').value.trim();
  const email = document.getElementById('u_email').value.trim();
  const password = document.getElementById('u_pass').value;
  await api('/api/admin/users', {
    method:'POST',
    headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},
    body: new URLSearchParams({ name, email, password }).toString()
  });
  document.getElementById('u_name').value='';
  document.getElementById('u_email').value='';
  document.getElementById('u_pass').value='';
  await loadUsers();
  await loadOverview();
});

document.getElementById('runHealth')?.addEventListener('click', loadHealth);
document.getElementById('runTests')?.addEventListener('click', async () => {
  await api('/api/admin/tests/run', { method:'POST' });
  await loadTests();
  await loadHealth();
});
document.getElementById('logoutBtn')?.addEventListener('click', async () => {
  await api('/api/admin/logout', { method:'POST' });
  location.href = '/admin/login.html';
});

function connectLogs() {
  const box = document.getElementById('logBox');
  const tail = document.getElementById('tailBox');
  const lvl = document.getElementById('lvlFilter');
  const find = document.getElementById('logFind');
  const clear = document.getElementById('btnClearLog');

  let lines = [];
  function render(){
    const lv = lvl?.value || '';
    const q = (find?.value || '').toLowerCase();
    const out = lines.filter(s =>
      (!lv || s.includes(lv)) && (!q || s.toLowerCase().includes(q))
    );
    if (box) { box.textContent = out.slice(-2000).join('\n'); box.scrollTop = box.scrollHeight; }
    if (tail) { tail.textContent = out.slice(-30).join('\n'); }
  }

  api('/api/admin/logs?limit=200').then(d => {
    lines = d.lines || [];
    render();
  });

  const es = new EventSource('/api/admin/logs/stream');
  es.onmessage = (ev) => {
    lines.push(ev.data);
    if (lines.length > 5000) lines = lines.slice(-5000);
    render();
  };
  es.onerror = () => {
    if (box) box.textContent += "\n[WARN] log stream disconnected";
    setTimeout(connectLogs, 1500);
    es.close();
  };

  lvl?.addEventListener('change', render);
  find?.addEventListener('input', render);
  clear?.addEventListener('click', () => { lines = []; render(); });
}

(async function init(){
  try{
    await loadOverview();
    await loadProjects();
    await loadUsers();
    await loadTests();
    connectLogs();
  }catch(e){
    console.error(e);
    location.href = '/admin/login.html';
  }
})();
