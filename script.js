// ---------------- Smooth scroll for buttons + tabs (data-link) ----------------
document.addEventListener("click", (e) => {
  const el = e.target.closest("[data-link]");
  if (!el) return;

  const target = document.querySelector(el.getAttribute("data-link"));
  if (!target) return;

  target.scrollIntoView({ behavior: "smooth" });
});

// ---------------- Navbar background on scroll ----------------
const navbar = document.querySelector(".navbar");
window.addEventListener("scroll", () => {
  if (!navbar) return;
  if (window.scrollY > 20) navbar.classList.add("scrolled");
  else navbar.classList.remove("scrolled");
});

// ---------------- Mobile menu toggle ----------------
const menuBtn = document.getElementById("menuBtn");
const mobileMenu = document.getElementById("mobileMenu");
const mobileMenuList = document.getElementById("mobileMenuList");

if (menuBtn && mobileMenu && mobileMenuList) {
  // copy desktop tabs into mobile menu list
  const tabs = document.querySelectorAll(".nav-tabs .tab");
  tabs.forEach((t) => {
    const li = document.createElement("li");
    li.textContent = t.textContent;
    li.setAttribute("data-link", t.getAttribute("data-link"));
    mobileMenuList.appendChild(li);
  });

  menuBtn.addEventListener("click", () => {
    const open = mobileMenu.classList.toggle("open");
    menuBtn.setAttribute("aria-expanded", open ? "true" : "false");
  });

  // close when click outside
  document.addEventListener("click", (e) => {
    if (!mobileMenu.classList.contains("open")) return;
    if (mobileMenu.contains(e.target) || menuBtn.contains(e.target)) return;
    mobileMenu.classList.remove("open");
    menuBtn.setAttribute("aria-expanded", "false");
  });

  // close after clicking item
  mobileMenu.addEventListener("click", (e) => {
    if (!e.target.closest("li")) return;
    mobileMenu.classList.remove("open");
    menuBtn.setAttribute("aria-expanded", "false");
  });
}

// ---------------- About role flip (coin flip) ----------------
const roles = [
  {
    name: "MuleSoft Developer",
    color: "#009dcd",
    desc: "I build API-led integrations using Anypoint Platform, RAML, and reusable assets."
  },
  {
    name: "Full Stack Developer",
    color: "#4d0199",
    desc: "I build complete web applications — frontend, backend, and database — with clean architecture."
  },
  {
    name: "UI/UX Developer",
    color: "#00c3ff",
    desc: "I design responsive UI with smooth interactions and a user-first experience."
  },
  {
    name: "API Integration Engineer",
    color: "#6300c6",
    desc: "I connect systems securely using REST/SOAP, OAuth, and scalable integration patterns."
  }
];

const roleCard = document.getElementById("roleCard");
const roleTag = document.getElementById("roleTag");
const roleTagBack = document.getElementById("roleTagBack");
const roleDesc = document.getElementById("roleDesc");

if (roleCard && roleTag && roleTagBack && roleDesc) {
  let i = 0;
  let front = true;

  function setRole(el, role){
    el.textContent = role.name;
    el.style.color = role.color;
  }

  // init
  setRole(roleTag, roles[0]);
  setRole(roleTagBack, roles[1]);
  roleDesc.textContent = roles[0].desc;

  setInterval(() => {
    i = (i + 1) % roles.length;
    const next = roles[i];

    // update hidden face before flip
    if (front) setRole(roleTagBack, next);
    else setRole(roleTag, next);

    roleDesc.textContent = next.desc;

    roleCard.classList.toggle("flip");
    front = !front;
  }, 2500);
}


// skills
// ===== Skills Coverflow =====
const flow = document.getElementById("skillsFlow");
const items = [...flow.querySelectorAll(".coverflow-item")];
const dotsWrap = document.getElementById("skillsDots");
const btnPrev = document.querySelector(".cf-btn.prev");
const btnNext = document.querySelector(".cf-btn.next");
const playBtn = document.getElementById("skillsPlay");

let currentIndex = 0;
let isAnimating = false;
let autoplay = true;
let autoplayTimer = null;

// dots
items.forEach((_, i) => {
  const d = document.createElement("div");
  d.className = "dot";
  d.addEventListener("click", () => goTo(i));
  dotsWrap.appendChild(d);
});
const dots = [...dotsWrap.querySelectorAll(".dot")];

function update(){
  if(isAnimating) return;
  isAnimating = true;

  items.forEach((item, index) => {
    let offset = index - currentIndex;

    // wrap
    if (offset > items.length / 2) offset -= items.length;
    if (offset < -items.length / 2) offset += items.length;

    const abs = Math.abs(offset);
    const sign = Math.sign(offset);

    let translateX = offset * 220;
    let translateZ = -abs * 220;
    let rotateY = -sign * Math.min(abs * 55, 55);
    let opacity = 1 - abs * 0.18;
    let scale = 1 - abs * 0.08;

    if(abs > 3){
      opacity = 0;
      translateX = sign * 900;
    }

    item.style.transform = `
      translateX(${translateX}px)
      translateZ(${translateZ}px)
      rotateY(${rotateY}deg)
      scale(${scale})
    `;
    item.style.opacity = opacity;
    item.style.zIndex = 100 - abs;
    item.classList.toggle("active", index === currentIndex);
  });

  dots.forEach((d, i) => d.classList.toggle("active", i === currentIndex));

  setTimeout(() => (isAnimating = false), 600);
}

function nav(dir){
  if(isAnimating) return;
  currentIndex += dir;
  if(currentIndex < 0) currentIndex = items.length - 1;
  if(currentIndex >= items.length) currentIndex = 0;
  update();
}

function goTo(i){
  if(isAnimating || i === currentIndex) return;
  currentIndex = i;
  update();
}

items.forEach((item, i) => item.addEventListener("click", () => goTo(i)));

btnPrev.addEventListener("click", () => { stopAutoplay(); nav(-1); });
btnNext.addEventListener("click", () => { stopAutoplay(); nav(1); });

document.querySelector(".skills-coverflow").addEventListener("keydown", (e) => {
  if(e.key === "ArrowLeft"){ stopAutoplay(); nav(-1); }
  if(e.key === "ArrowRight"){ stopAutoplay(); nav(1); }
});

// swipe
let sx=0, ex=0;
flow.addEventListener("touchstart", (e)=>{ sx = e.touches[0].clientX; }, {passive:true});
flow.addEventListener("touchend", (e)=>{
  ex = e.changedTouches[0].clientX;
  const diff = sx - ex;
  if(Math.abs(diff) > 35){
    stopAutoplay();
    if(diff > 0) nav(1);
    else nav(-1);
  }
}, {passive:true});

// autoplay
function startAutoplay(){
  autoplay = true;
  playBtn.textContent = "⏸";
  autoplayTimer = setInterval(()=> nav(1), 3500);
}
function stopAutoplay(){
  autoplay = false;
  playBtn.textContent = "▶";
  if(autoplayTimer){ clearInterval(autoplayTimer); autoplayTimer = null; }
}
playBtn.addEventListener("click", ()=>{
  if(autoplay) stopAutoplay();
  else startAutoplay();
});

// init
update();
startAutoplay();
// 


// ===== Blogs Coverflow =====
const blogFlow = document.getElementById("blogFlow");
const blogItems = [...blogFlow.querySelectorAll(".blog-card")];
const blogDotsWrap = document.getElementById("blogDots");
const blogPrev = document.querySelector(".blog-btn.prev");
const blogNext = document.querySelector(".blog-btn.next");
const blogPlay = document.getElementById("blogPlay");

let blogIndex = 0;
let blogAnimating = false;
let blogAuto = true;
let blogTimer = null;

// dots
blogItems.forEach((_, i) => {
  const d = document.createElement("div");
  d.className = "blog-dot";
  d.addEventListener("click", () => blogGo(i));
  blogDotsWrap.appendChild(d);
});
const blogDots = [...blogDotsWrap.querySelectorAll(".blog-dot")];

function blogUpdate(){
  if(blogAnimating) return;
  blogAnimating = true;

  blogItems.forEach((item, index) => {
    let offset = index - blogIndex;

    if (offset > blogItems.length / 2) offset -= blogItems.length;
    if (offset < -blogItems.length / 2) offset += blogItems.length;

    const abs = Math.abs(offset);
    const sign = Math.sign(offset);

    let translateX = offset * 240;
    let translateZ = -abs * 240;
    let rotateY = -sign * Math.min(abs * 55, 55);
    let opacity = 1 - abs * 0.18;
    let scale = 1 - abs * 0.08;

    if(abs > 3){
      opacity = 0;
      translateX = sign * 900;
    }

    item.style.transform = `
      translateX(${translateX}px)
      translateZ(${translateZ}px)
      rotateY(${rotateY}deg)
      scale(${scale})
    `;
    item.style.opacity = opacity;
    item.style.zIndex = 100 - abs;
    item.classList.toggle("active", index === blogIndex);
  });

  blogDots.forEach((d, i) => d.classList.toggle("active", i === blogIndex));

  setTimeout(() => (blogAnimating = false), 600);
}

function blogNav(dir){
  if(blogAnimating) return;
  blogIndex += dir;
  if(blogIndex < 0) blogIndex = blogItems.length - 1;
  if(blogIndex >= blogItems.length) blogIndex = 0;
  blogUpdate();
}

function blogGo(i){
  if(blogAnimating || i === blogIndex) return;
  blogIndex = i;
  blogUpdate();
}

blogItems.forEach((item, i) => item.addEventListener("click", () => blogGo(i)));

blogPrev.addEventListener("click", () => { blogStop(); blogNav(-1); });
blogNext.addEventListener("click", () => { blogStop(); blogNav(1); });

document.querySelector(".blogs-coverflow").addEventListener("keydown", (e) => {
  if(e.key === "ArrowLeft"){ blogStop(); blogNav(-1); }
  if(e.key === "ArrowRight"){ blogStop(); blogNav(1); }
});

// swipe
let bsx=0, bex=0;
blogFlow.addEventListener("touchstart", (e)=>{ bsx = e.touches[0].clientX; }, {passive:true});
blogFlow.addEventListener("touchend", (e)=>{
  bex = e.changedTouches[0].clientX;
  const diff = bsx - bex;
  if(Math.abs(diff) > 35){
    blogStop();
    if(diff > 0) blogNav(1);
    else blogNav(-1);
  }
}, {passive:true});

// autoplay
function blogStart(){
  blogAuto = true;
  blogPlay.textContent = "⏸";
  blogTimer = setInterval(()=> blogNav(1), 5000);
}
function blogStop(){
  blogAuto = false;
  blogPlay.textContent = "▶";
  if(blogTimer){ clearInterval(blogTimer); blogTimer = null; }
}

blogPlay.addEventListener("click", ()=>{
  if(blogAuto) blogStop();
  else blogStart();
});

// init
blogUpdate();
blogStart();


// contact

const contactForm = document.getElementById("contactForm");
if(contactForm){
  contactForm.addEventListener("submit", (e) => {
    e.preventDefault();
    alert("Thanks! Your message is noted ✅");
    contactForm.reset();
  });
}

