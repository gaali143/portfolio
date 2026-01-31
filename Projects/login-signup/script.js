// Toggle panels
const container = document.getElementById('container');
const registerBtn = document.getElementById('register');
const loginBtn = document.getElementById('login');

registerBtn.addEventListener('click', () => {
  container.classList.add('active');
});

loginBtn.addEventListener('click', () => {
  container.classList.remove('active');
});

// Same server serves frontend + API => use relative base
const API_BASE = '/api';

// helper: convert object to x-www-form-urlencoded
function toFormBody(obj) {
  return Object.entries(obj)
    .map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v ?? ''))
    .join('&');
}

// safer JSON read (won't crash if server returns plain text)
async function readJsonSafe(res) {
  const text = await res.text();
  try { return JSON.parse(text); }
  catch { return { error: text || 'Invalid response' }; }
}

// ===== SIGN UP =====
const signUpForm = document.querySelector('.sign-up form');

signUpForm.addEventListener('submit', async (e) => {
  e.preventDefault();

  const [nameInput, emailInput, passwordInput, confirmInput] =
    signUpForm.querySelectorAll('input');

  const payload = {
    name: nameInput.value.trim(),
    email: emailInput.value.trim(),
    password: passwordInput.value,
    confirmPassword: confirmInput.value,
  };

  try {
    const res = await fetch(`${API_BASE}/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body: toFormBody(payload),
    });

    const data = await readJsonSafe(res);

    if (!res.ok) {
      alert('Sign up failed: ' + (data.error || 'Unknown error'));
      return;
    }

    alert(`Registered! Welcome, ${data.user.name}`);
    signUpForm.reset();
    container.classList.remove('active');
  } catch (err) {
    console.error(err);
    alert('Sign up error');
  }
});

// ===== SIGN IN =====
const signInForm = document.querySelector('.sign-in form');

signInForm.addEventListener('submit', async (e) => {
  e.preventDefault();

  const [usernameInput, passwordInput] = signInForm.querySelectorAll('input');

  const payload = {
    // "Username" field is actually email
    email: usernameInput.value.trim(),
    password: passwordInput.value,
  };

  try {
    const res = await fetch(`${API_BASE}/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body: toFormBody(payload),
    });

    const data = await readJsonSafe(res);

    if (!res.ok) {
      alert('Login failed: ' + (data.error || 'Unknown error'));
      return;
    }

    alert(`Login successful! Hello, ${data.user.name}`);
    console.log('Logged in user:', data.user);
  } catch (err) {
    console.error(err);
    alert('Login error');
  }
});
