/**
 * Full HTML documents for the two Worker-served sites.
 *
 *   personalHomePage()   -> su46proj.site / www.su46proj.site  (personal / editorial)
 *   projectLandingPage() -> pay.su46proj.site                  (MiniPay AI product page)
 *
 * Both are complete, standalone HTML5 documents with zero network requests:
 * no <script>, no external <link>, no @import, no web fonts, no analytics,
 * no images. The only outbound references are the portfolio <a href> URLs.
 *
 * Design: warm off-white paper, charcoal ink, hairline rules, a single
 * terracotta accent. Depth comes from 1px borders, flat surface tints and
 * very soft shadows -- never gradients, glow or blur.
 */

/* ------------------------------------------------------------------ */
/* constants                                                           */
/* ------------------------------------------------------------------ */

const ICP_NUMBER = '豫ICP备2026043015号';
const ICP_URL = 'https://beian.miit.gov.cn/';
const AUTHOR_EMAIL = 'su_qihang@163.com';
const AUTHOR_MAILTO = 'mailto:su_qihang@163.com';
const SITE_URL = 'https://su46proj.site/';
const APK_URL = 'https://download.su46proj.site/downloads/minipay-latest.apk';
const PROJECT_URL = 'https://pay.su46proj.site/';

/* ------------------------------------------------------------------ */
/* PROJECTS -- add future projects here.                               */
/*                                                                     */
/* This array is the single place to publish a project. Add one object */
/* and a card appears on the personal homepage. Fields:                */
/*   name         card title                                           */
/*   tagline      one short line under the title                       */
/*   description  1-2 sentence description                             */
/*   tags         string[] of short labels                             */
/*   url          where the card links to                              */
/*   status       short status label shown in the card header band     */
/*   featured     true for the wide anchor card                        */
/*   placeholder  true for the dashed "more coming" card (no link)     */
/* ------------------------------------------------------------------ */

const PROJECTS = [
  {
    name: 'MiniPay AI',
    tagline: '数字支付 / 钱包 / 外卖一体化演示平台',
    description:
      '我自己完整实现并长期维护的一套支付与本地生活演示平台。从统一身份认证、复式账本钱包到支付编排、对账与外卖配送，全部跑在自建的 K3s 集群上，并带一个只能调用白名单工具的 AI 助手。',
    tags: ['Java 21', 'Spring Boot 3', 'K3s', 'OAuth2 + PKCE', '复式账本'],
    url: PROJECT_URL,
    status: '在线运行',
    featured: true,
  },
  {
    name: '更多项目正在路上',
    tagline: '这里会继续追加',
    description:
      '这个主页是长期维护的，后面做完的项目会直接加到上面的列表里。项目列表由数据驱动，加一条记录就多一张卡片。',
    tags: [],
    url: '',
    status: '规划中',
    placeholder: true,
  },
];

/** Grounded platform facts. No invented achievements or numbers. */
const FACTS = [
  { n: '6', k: '个后端微服务', d: '各自独占数据' },
  { n: '15', k: '个运行中工作负载', d: '同一套 K3s' },
  { n: '5', k: '个 Web 端入口', d: '含 Android App' },
  { n: '1', k: '套自建 K3s 集群', d: 'Cloudflare 回源' },
];

/** Technology stack, grouped so the page reads as panels rather than a list. */
const TECH_GROUPS = [
  {
    name: '后端',
    note: '服务与账务',
    items: ['Java 21', 'Spring Boot 3', 'MySQL', 'Redis', 'RabbitMQ', 'Seata'],
  },
  {
    name: '基础设施',
    note: '编排与网关',
    items: ['Kubernetes (K3s)', 'NGINX Gateway Fabric', 'Docker', 'TLS / Origin CA'],
  },
  {
    name: '前端',
    note: 'B 端控制台',
    items: ['React', 'umi', 'TypeScript', '响应式布局'],
  },
  {
    name: '移动端',
    note: '用户端 App',
    items: ['Android', 'Jetpack Compose', 'Kotlin'],
  },
  {
    name: '云与边缘',
    note: '接入与分发',
    items: ['Cloudflare Worker', 'R2 对象存储', 'DNS / WAF', '边缘缓存'],
  },
];

/* ------------------------------------------------------------------ */
/* shared CSS                                                          */
/* ------------------------------------------------------------------ */

const SHARED_CSS = `
:root{
  color-scheme:light;
  /* surfaces -- warm neutrals, flat tints, no gradients */
  --paper:#faf9f7;
  --paper-2:#f4f2ee;
  --paper-3:#ebe8e1;
  --card:#ffffff;
  --ink:#131418;
  --text:#3e3d38;
  --muted:#78756c;
  --rule:#e0ddd5;
  --rule-2:#cbc7bc;
  /* single accent, used sparingly */
  --accent:#b04a29;
  --accent-2:#8d391d;
  --accent-wash:#f7ede7;
  /* type */
  --sans:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif;
  --mono:ui-monospace,SFMono-Regular,"SF Mono",Menlo,Consolas,"Liberation Mono",monospace;
  /* rhythm */
  --col:960px;
  --wide:1140px;
  --gut:clamp(20px,5vw,56px);
  --sec:clamp(58px,8vw,104px);
  --hair:1px solid var(--rule);
  --radius:8px;
  --radius-sm:5px;
  --shadow-1:0 1px 2px rgba(19,20,24,.05);
  --shadow-2:0 1px 2px rgba(19,20,24,.05), 0 16px 34px -26px rgba(19,20,24,.5);
  --ease:160ms cubic-bezier(.32,.72,0,1);
}
*{box-sizing:border-box}
html{-webkit-text-size-adjust:100%;scroll-behavior:smooth}
body{
  margin:0;
  background:var(--paper);
  color:var(--text);
  font-family:var(--sans);
  font-size:clamp(15.5px,1.02vw,17px);
  line-height:1.78;
  letter-spacing:.004em;
  -webkit-font-smoothing:antialiased;
  text-rendering:optimizeLegibility;
  overflow-x:hidden;
}
h1,h2,h3,h4{margin:0;color:var(--ink);font-weight:600;letter-spacing:-.02em;line-height:1.24}
h1{font-size:clamp(2.2rem,5.4vw,3.5rem);letter-spacing:-.035em;line-height:1.08}
h2{font-size:clamp(1.5rem,3.2vw,2.1rem);letter-spacing:-.028em;line-height:1.2}
h3{font-size:clamp(1.06rem,1.6vw,1.24rem);letter-spacing:-.015em;line-height:1.35}
p{margin:0}
ul,ol,dl,dd{margin:0;padding:0}
li{list-style:none}
a{color:var(--accent);text-decoration:none;transition:color var(--ease),border-color var(--ease),background var(--ease),box-shadow var(--ease),transform var(--ease)}
a:hover{color:var(--accent-2)}
code,kbd{font-family:var(--mono);font-size:.88em;background:var(--paper-2);border:var(--hair);border-radius:4px;padding:2px 7px;color:var(--ink);white-space:nowrap}
strong,b{font-weight:600;color:var(--ink)}
::selection{background:var(--accent);color:#fff}
:focus-visible{outline:2px solid var(--accent);outline-offset:3px;border-radius:2px}

/* ---------- layout ---------- */
.col{max-width:var(--col);margin:0 auto;padding:0 var(--gut)}
.wide{max-width:var(--wide);margin:0 auto;padding:0 var(--gut)}
section{padding:var(--sec) 0}
.lede{font-size:clamp(1.04rem,1.5vw,1.2rem);line-height:1.8;color:var(--text);max-width:62ch}
.body{max-width:68ch}
.muted{color:var(--muted)}
.mark{display:inline-block;width:8px;height:8px;background:var(--accent);flex:0 0 auto}

/* ---------- numbered section header ---------- */
.sec-head{padding-top:22px;border-top:1px solid var(--ink);margin-bottom:clamp(30px,4.4vw,48px)}
.sec-head .eyebrow{display:flex;align-items:center;gap:14px;margin-bottom:18px}
.sec-head .num{font-family:var(--mono);font-size:.86rem;font-weight:600;letter-spacing:.06em;color:var(--accent)}
.sec-head .eyebrow .rule{flex:1 1 auto;height:1px;background:var(--rule);min-width:24px}
.sec-head .eyebrow .lbl{font-family:var(--mono);font-size:.75rem;letter-spacing:.17em;text-transform:uppercase;color:var(--muted)}
.sec-head h2{max-width:26ch}
.sec-head .sub{margin-top:14px;max-width:62ch;color:var(--muted);font-size:1rem}

/* ---------- nav ---------- */
.site-head{position:sticky;top:0;z-index:50;background:var(--paper-2);border-bottom:var(--hair);box-shadow:var(--shadow-1)}
.site-head .in{max-width:var(--wide);margin:0 auto;padding:0 var(--gut);display:flex;align-items:center;gap:22px;min-height:64px}
.wordmark{display:inline-flex;align-items:baseline;gap:9px;color:var(--ink);font-weight:600;letter-spacing:-.01em;font-size:.98rem;white-space:nowrap}
.wordmark:hover{color:var(--accent)}
.wordmark .sep{width:5px;height:5px;background:var(--accent);display:inline-block;transform:translateY(-2px)}
.nav{margin-left:auto;display:flex;align-items:center;flex-wrap:wrap;gap:3px}
.nav a{display:inline-flex;align-items:center;gap:7px;padding:8px 11px;border-radius:var(--radius-sm);font-size:.9rem;color:var(--muted);border:1px solid transparent;transition:color var(--ease),background var(--ease),border-color var(--ease)}
.nav a .n{font-family:var(--mono);font-size:.7rem;color:var(--rule-2);transition:color var(--ease)}
.nav a:hover{color:var(--ink);background:var(--paper-3);border-color:var(--rule)}
.nav a:hover .n{color:var(--accent)}
.nav a.out{color:var(--ink)}
.nav a.out::after{content:"\\2197";font-size:.74em;margin-left:2px;color:var(--muted)}

/* ---------- hero ---------- */
.hero{padding:clamp(52px,8vw,104px) 0 clamp(36px,5vw,60px)}
.status-pill{
  display:inline-flex;align-items:center;gap:9px;
  font-family:var(--mono);font-size:.75rem;letter-spacing:.11em;
  color:var(--accent-2);background:var(--accent-wash);
  border:1px solid #e8d3c7;border-radius:999px;padding:6px 14px 6px 11px;
}
.status-pill i{width:7px;height:7px;border-radius:50%;background:var(--accent);display:inline-block;flex:0 0 auto}
.hero h1{margin-top:clamp(20px,2.8vw,28px);max-width:22ch}
.hero .lede{margin-top:clamp(18px,2.4vw,26px)}
.hero .acts{display:flex;flex-wrap:wrap;gap:12px;margin-top:clamp(26px,3.4vw,36px)}

/* buttons -- solid primary + outlined secondary */
.btn{
  display:inline-flex;align-items:center;justify-content:center;gap:9px;
  padding:13px 24px;border-radius:var(--radius-sm);
  font-size:.96rem;font-weight:500;line-height:1.2;
  border:1px solid var(--rule-2);background:var(--card);color:var(--ink);
  box-shadow:var(--shadow-1);cursor:pointer;
  transition:background var(--ease),border-color var(--ease),color var(--ease),box-shadow var(--ease),transform var(--ease);
}
.btn:hover{background:var(--paper-2);border-color:var(--ink);color:var(--ink);box-shadow:var(--shadow-2);transform:translateY(-1px)}
.btn:active{transform:translateY(0);box-shadow:none}
.btn .arrow{transition:transform var(--ease)}
.btn:hover .arrow{transform:translateX(4px)}
.btn.solid{background:var(--ink);border-color:var(--ink);color:#fff}
.btn.solid:hover{background:var(--accent);border-color:var(--accent);color:#fff}
.btn.tiny{padding:9px 16px;font-size:.88rem}
.btn.block{width:100%}

/* ---------- facts strip ---------- */
.facts{
  display:grid;grid-template-columns:repeat(auto-fit,minmax(168px,1fr));gap:1px;
  background:var(--rule);border:var(--hair);border-radius:var(--radius);
  overflow:hidden;margin-top:clamp(38px,5.2vw,56px);box-shadow:var(--shadow-1);
}
.facts .cell{background:var(--card);padding:clamp(20px,2.6vw,28px)}
.facts .n{display:block;font-size:clamp(1.9rem,4vw,2.6rem);font-weight:600;letter-spacing:-.04em;line-height:1;color:var(--ink)}
.facts .k{display:block;margin-top:11px;font-size:.92rem;color:var(--text);font-weight:500}
.facts .d{display:block;margin-top:5px;font-family:var(--mono);font-size:.74rem;letter-spacing:.06em;color:var(--muted)}

/* ---------- editorial two-column (about) ---------- */
.editorial{display:grid;grid-template-columns:minmax(0,15rem) minmax(0,1fr);gap:clamp(24px,5vw,60px);align-items:start}
.editorial .aside{
  position:sticky;top:96px;background:var(--card);border:var(--hair);
  border-radius:var(--radius);padding:22px;box-shadow:var(--shadow-1);
}
.editorial .aside .k{font-family:var(--mono);font-size:.72rem;letter-spacing:.15em;text-transform:uppercase;color:var(--muted)}
.editorial .aside ul{margin-top:14px;display:grid;gap:11px}
.editorial .aside li{display:flex;align-items:baseline;gap:10px;font-size:.94rem;color:var(--ink);line-height:1.6}
.editorial .aside li i{width:5px;height:5px;background:var(--accent);display:inline-block;flex:0 0 auto;transform:translateY(-3px)}
.editorial .prose p + p{margin-top:1.15em}

/* ---------- grouped tech panels ---------- */
.groups{display:grid;grid-template-columns:repeat(auto-fit,minmax(258px,1fr));gap:clamp(14px,1.8vw,20px)}
.gpanel{
  background:var(--card);border:var(--hair);border-radius:var(--radius);
  padding:22px;box-shadow:var(--shadow-1);display:flex;flex-direction:column;
  transition:border-color var(--ease),box-shadow var(--ease);
}
.gpanel:hover{border-color:var(--rule-2);box-shadow:var(--shadow-2)}
.gpanel .gtop{display:flex;align-items:baseline;justify-content:space-between;gap:12px;padding-bottom:14px;border-bottom:var(--hair)}
.gpanel .gtop h3{font-size:1.02rem}
.gpanel .gtop .note{font-family:var(--mono);font-size:.72rem;letter-spacing:.08em;color:var(--muted);white-space:nowrap}
.gpanel .chips{margin-top:16px;display:flex;flex-wrap:wrap;gap:8px}

.chip{
  display:inline-block;font-family:var(--mono);font-size:.78rem;letter-spacing:.01em;
  color:var(--text);background:var(--paper-2);border:var(--hair);border-radius:var(--radius-sm);
  padding:5px 10px;
  transition:color var(--ease),background var(--ease),border-color var(--ease);
}
.chip:hover{color:var(--accent-2);background:var(--accent-wash);border-color:#e8d3c7}

/* ---------- project cards ---------- */
.plist{display:grid;gap:clamp(16px,2.2vw,24px)}
.pcard{
  border:var(--hair);border-radius:var(--radius);background:var(--card);
  overflow:hidden;box-shadow:var(--shadow-1);
  transition:border-color var(--ease),box-shadow var(--ease),transform var(--ease);
}
.pcard:hover{border-color:var(--rule-2);box-shadow:var(--shadow-2);transform:translateY(-2px)}
.pcard .band{
  display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:12px;
  padding:16px clamp(20px,3vw,34px);
  background:var(--paper-2);border-bottom:var(--hair);
  border-left:3px solid var(--accent);
}
.pcard .band .nm{font-weight:600;color:var(--ink);font-size:1.02rem;letter-spacing:-.01em}
.pcard .band .tag{font-family:var(--mono);font-size:.72rem;letter-spacing:.14em;text-transform:uppercase;color:var(--muted);margin-left:12px}
.pcard .inner{padding:clamp(22px,3.2vw,34px)}
.pcard h3{font-size:clamp(1.22rem,2.4vw,1.6rem);letter-spacing:-.028em}
.pcard .tagline{margin-top:9px;color:var(--accent);font-size:.96rem;font-weight:500}
.pcard .desc{margin-top:16px;color:var(--text);max-width:66ch;font-size:.98rem}
.pcard .meta{margin-top:20px;display:flex;flex-wrap:wrap;gap:8px}
.pcard .foot{margin-top:clamp(20px,2.6vw,28px);padding-top:clamp(18px,2.4vw,24px);border-top:var(--hair);display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:14px}
.status{display:inline-flex;align-items:center;gap:7px;font-family:var(--mono);font-size:.73rem;letter-spacing:.11em;text-transform:uppercase;color:var(--accent);background:var(--accent-wash);border:1px solid #e8d3c7;border-radius:3px;padding:4px 9px;white-space:nowrap}
.status i{width:6px;height:6px;background:var(--accent);border-radius:50%;display:inline-block}

/* dashed "more coming" card */
.pcard.ghost{border-style:dashed;border-color:var(--rule-2);background:transparent;box-shadow:none}
.pcard.ghost:hover{border-color:var(--rule-2);box-shadow:none;transform:none}
.pcard.ghost .band{background:transparent;border-bottom:1px dashed var(--rule-2);border-left-color:var(--rule-2)}
.pcard.ghost .band .nm{color:var(--muted)}
.pcard.ghost h3{color:var(--muted);font-size:clamp(1.04rem,1.8vw,1.24rem)}
.pcard.ghost .desc{color:var(--muted);font-size:.94rem}
.pcard.ghost .plus{font-family:var(--mono);font-size:.8rem;letter-spacing:.1em;color:var(--muted)}

/* ---------- contact card ---------- */
.ccard{background:var(--card);border:var(--hair);border-radius:var(--radius);box-shadow:var(--shadow-2);overflow:hidden}
.ccard .ctop{padding:clamp(26px,3.6vw,42px);border-bottom:var(--hair)}
.ccard .ctop p{color:var(--text);max-width:58ch;font-size:1rem}
.ccard .mailrow{display:flex;flex-wrap:wrap;align-items:center;gap:16px;margin-top:clamp(20px,2.6vw,28px)}
.ccard .mail{
  display:inline-flex;align-items:center;gap:12px;
  font-size:clamp(1.08rem,2.4vw,1.5rem);font-weight:600;letter-spacing:-.02em;
  color:var(--ink);background:var(--paper-2);border:1px solid var(--rule-2);
  border-radius:var(--radius-sm);padding:14px 22px;word-break:break-all;
  transition:background var(--ease),border-color var(--ease),color var(--ease),box-shadow var(--ease),transform var(--ease);
}
.ccard .mail:hover{background:var(--accent);border-color:var(--accent);color:#fff;box-shadow:var(--shadow-2);transform:translateY(-1px)}
.ccard .mail .arrow{transition:transform var(--ease)}
.ccard .mail:hover .arrow{transform:translateX(4px)}
.ccard .facts2{display:grid;grid-template-columns:repeat(auto-fit,minmax(216px,1fr));gap:1px;background:var(--rule)}
.ccard .facts2 .cell{background:var(--card);padding:22px clamp(20px,3vw,28px)}
.ccard .facts2 .k{font-family:var(--mono);font-size:.72rem;letter-spacing:.14em;text-transform:uppercase;color:var(--muted)}
.ccard .facts2 .v{margin-top:9px;color:var(--ink);font-size:.94rem;line-height:1.7}

/* ---------- generic grid / cell cards (product page) ---------- */
.grid{display:grid;gap:1px;background:var(--rule);border:var(--hair);border-radius:var(--radius);overflow:hidden;box-shadow:var(--shadow-1)}
.g3{grid-template-columns:repeat(auto-fit,minmax(276px,1fr))}
.cell-card{background:var(--card);padding:clamp(22px,2.8vw,30px);display:flex;flex-direction:column;transition:background var(--ease)}
.cell-card:hover{background:#fdf8f4}
.cell-card .no{font-family:var(--mono);font-size:.74rem;letter-spacing:.14em;color:var(--accent);margin-bottom:16px}
.cell-card h3{margin-bottom:11px}
.cell-card p{color:var(--muted);font-size:.94rem;line-height:1.76}
.cell-card .chips{margin-top:auto;padding-top:20px;display:flex;flex-wrap:wrap;gap:7px}

/* architecture ladder */
.ladder{border-top:var(--hair)}
.ladder .rung{display:grid;grid-template-columns:minmax(0,13rem) minmax(0,1fr);gap:clamp(14px,3vw,40px);padding:20px 0;border-bottom:var(--hair)}
.ladder .rung .lname{color:var(--ink);font-weight:600;font-size:.96rem}
.ladder .rung .lname span{display:block;font-family:var(--mono);font-weight:400;font-size:.72rem;letter-spacing:.12em;color:var(--muted);margin-top:6px}
.ladder .rung .ldesc{color:var(--muted);font-size:.94rem;line-height:1.76;max-width:66ch}

/* credentials table */
.tbl-wrap{border:var(--hair);border-radius:var(--radius);background:var(--card);overflow-x:auto;-webkit-overflow-scrolling:touch;box-shadow:var(--shadow-1)}
table{border-collapse:collapse;width:100%;min-width:600px}
th,td{text-align:left;padding:15px 20px;border-bottom:var(--hair);font-size:.93rem;vertical-align:top}
th{font-family:var(--mono);font-size:.72rem;letter-spacing:.13em;text-transform:uppercase;color:var(--muted);font-weight:500;background:var(--paper-2)}
tbody tr:last-child td{border-bottom:0}
tbody tr:hover td{background:#fdf8f4}
td.sys{color:var(--ink);font-weight:600;white-space:nowrap}

/* steps */
.steps{counter-reset:st;border-top:var(--hair)}
.steps li{counter-increment:st;display:grid;grid-template-columns:3.4rem minmax(0,1fr);gap:clamp(10px,2vw,24px);padding:22px 0;border-bottom:var(--hair)}
.steps li::before{content:counter(st,decimal-leading-zero);font-family:var(--mono);font-size:.82rem;color:var(--accent);padding-top:4px}
.steps b{display:block;color:var(--ink);font-weight:600;margin-bottom:6px}
.steps p{color:var(--muted);font-size:.94rem;line-height:1.74;max-width:68ch}

/* module entry cards */
.entries{display:grid;grid-template-columns:repeat(auto-fit,minmax(272px,1fr));gap:clamp(14px,1.8vw,20px)}
.ecard{
  display:flex;flex-direction:column;background:var(--card);border:var(--hair);
  border-radius:var(--radius);padding:22px;box-shadow:var(--shadow-1);
  transition:border-color var(--ease),box-shadow var(--ease),transform var(--ease);
}
.ecard:hover{border-color:var(--rule-2);box-shadow:var(--shadow-2);transform:translateY(-2px)}
.ecard .etop{display:flex;align-items:center;justify-content:space-between;gap:12px}
.ecard .etop .nm{font-weight:600;color:var(--ink);font-size:1.02rem}
.ecard .etop .arrow{color:var(--accent);transition:transform var(--ease)}
.ecard:hover .etop .arrow{transform:translateX(4px)}
.ecard .desc{margin-top:8px;color:var(--text);font-size:.94rem}
.ecard .host{margin-top:16px;padding-top:14px;border-top:var(--hair);font-family:var(--mono);font-size:.79rem;color:var(--muted);word-break:break-all}

/* notices / bands */
.notice{display:flex;gap:16px;align-items:flex-start;border:1px solid #e8d3c7;background:var(--accent-wash);border-radius:var(--radius);padding:22px clamp(20px,3vw,28px)}
.notice .mark{margin-top:9px}
.notice p{color:#6f4433;font-size:.94rem;line-height:1.76;max-width:74ch}
.band2{
  display:flex;flex-wrap:wrap;gap:clamp(20px,4vw,48px);align-items:center;justify-content:space-between;
  border:var(--hair);background:var(--card);border-radius:var(--radius);
  padding:clamp(24px,3.4vw,38px);box-shadow:var(--shadow-2);
}
.band2 .t{max-width:52ch}
.band2 .t h3{font-size:clamp(1.14rem,2.2vw,1.42rem)}
.band2 .t p{color:var(--muted);font-size:.94rem;margin-top:10px}
.band2 .f{font-family:var(--mono);font-size:.79rem;color:var(--muted);margin-top:14px;word-break:break-all}
.note-line{margin-top:20px;color:var(--muted);font-size:.9rem;max-width:72ch}
.callout-home{display:flex;flex-wrap:wrap;gap:16px;justify-content:space-between;align-items:center;border-top:var(--hair);padding-top:26px}
.callout-home p{color:var(--muted);font-size:.94rem;max-width:60ch}

/* ---------- footer ---------- */
.site-foot{border-top:1px solid var(--ink);background:var(--paper-2);margin-top:clamp(48px,7vw,90px)}
.site-foot .top{max-width:var(--wide);margin:0 auto;padding:clamp(36px,5vw,58px) var(--gut) 0;display:flex;flex-wrap:wrap;gap:clamp(24px,4vw,56px);justify-content:space-between}
.site-foot .bio{max-width:42ch}
.site-foot .bio p{color:var(--muted);font-size:.9rem;margin-top:12px}
.site-foot .fnav{display:flex;flex-wrap:wrap;gap:8px 22px;align-content:flex-start;max-width:34rem}
.site-foot .fnav a{color:var(--text);font-size:.9rem;border-bottom:1px solid transparent;padding-bottom:2px}
.site-foot .fnav a:hover{color:var(--accent);border-bottom-color:var(--accent)}
.site-foot .bottom{max-width:var(--wide);margin:clamp(30px,4vw,46px) auto 0;padding:22px var(--gut) 40px;border-top:var(--hair);display:flex;flex-wrap:wrap;gap:12px 26px;align-items:center;justify-content:space-between;color:var(--muted);font-size:.85rem}
.site-foot .bottom .right{display:flex;flex-wrap:wrap;gap:12px 24px;align-items:center}
.site-foot .icp{color:var(--text);font-family:var(--mono);font-size:.82rem;border-bottom:1px solid var(--rule-2);padding-bottom:2px}
.site-foot .icp:hover{color:var(--accent);border-bottom-color:var(--accent)}
.disclaimer{max-width:var(--wide);color:var(--muted);font-size:.86rem;line-height:1.8;margin:18px auto 0;padding:0 var(--gut)}
.sr{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}

/* ---------- responsive ---------- */
@media (max-width:900px){
  .editorial{grid-template-columns:1fr}
  .editorial .aside{position:static}
}
@media (max-width:720px){
  body{font-size:16px;line-height:1.76}
  .site-head .in{min-height:56px;gap:12px;flex-wrap:wrap;padding-top:9px;padding-bottom:9px}
  .nav{width:100%;margin-left:0;gap:2px}
  .nav a{padding:7px 9px;font-size:.86rem}
  .nav a .n{display:none}
  .hero{padding:40px 0 28px}
  .hero h1{max-width:none}
  .hero .acts .btn{flex:1 1 auto}
  .facts{grid-template-columns:repeat(2,minmax(0,1fr))}
  .facts .cell{padding:18px 16px}
  .editorial .aside{padding:18px}
  .groups{grid-template-columns:1fr}
  .pcard .band{padding:14px 18px}
  .pcard .band .tag{display:none}
  .pcard .foot{flex-direction:column;align-items:flex-start}
  .pcard .foot .btn{width:100%}
  .ccard .mailrow{flex-direction:column;align-items:stretch}
  .ccard .mail{justify-content:space-between}
  .ccard .mailrow .btn{width:100%}
  .entries{grid-template-columns:1fr}
  .ladder .rung{grid-template-columns:1fr;gap:8px}
  .steps li{grid-template-columns:2.4rem minmax(0,1fr);gap:12px}
  .band2{flex-direction:column;align-items:stretch}
  .band2 .btn{width:100%}
  .callout-home{flex-direction:column;align-items:stretch}
  .callout-home .btn{width:100%}
  .site-foot .bottom{flex-direction:column;align-items:flex-start}
}
@media (prefers-reduced-motion:reduce){
  html{scroll-behavior:auto}
  *{transition:none!important;animation:none!important}
  .pcard:hover,.ecard:hover,.btn:hover,.ccard .mail:hover{transform:none}
  .btn:hover .arrow,.ecard:hover .etop .arrow,.ccard .mail:hover .arrow{transform:none}
}
`;

/* ------------------------------------------------------------------ */
/* shared fragments                                                    */
/* ------------------------------------------------------------------ */

const HEAD_META = `<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">`;

function icpLink() {
  return '<a class="icp" href="' + ICP_URL + '" target="_blank" rel="noopener">' + ICP_NUMBER + '</a>';
}

/**
 * Numbered section header: "01 --- 关于我" over a top rule, then the title.
 * @param {string} num    two-digit index
 * @param {string} label  section label
 * @param {string} title  section heading
 * @param {string} sub    optional supporting line
 */
function sectionHead(num, label, title, sub) {
  const subLine = sub ? '\n      <p class="sub">' + sub + '</p>' : '';
  return `    <div class="sec-head">
      <p class="eyebrow"><span class="num">${num}</span><span class="rule" aria-hidden="true"></span><span class="lbl">${label}</span></p>
      <h2>${title}</h2>${subLine}
    </div>`;
}

function renderFacts() {
  return FACTS.map(function (f) {
    return `      <div class="cell"><span class="n">${f.n}</span><span class="k">${f.k}</span><span class="d">${f.d}</span></div>`;
  }).join('\n');
}

function renderTechGroups() {
  return TECH_GROUPS.map(function (g) {
    const chips = g.items
      .map(function (i) {
        return '<span class="chip">' + i + '</span>';
      })
      .join('');
    return `      <div class="gpanel">
        <div class="gtop"><h3>${g.name}</h3><span class="note">${g.note}</span></div>
        <div class="chips">${chips}</div>
      </div>`;
  }).join('\n');
}

/** Render the PROJECTS array: real cards get a header band, placeholders go dashed. */
function renderProjects() {
  return PROJECTS.map(function (p) {
    if (p.placeholder) {
      return `      <article class="pcard ghost">
        <div class="band">
          <span class="nm">${p.name}</span>
          <span class="plus">预留位置</span>
        </div>
        <div class="inner">
          <h3>${p.tagline}</h3>
          <p class="desc">${p.description}</p>
        </div>
      </article>`;
    }
    const chips = p.tags
      .map(function (t) {
        return '<span class="chip">' + t + '</span>';
      })
      .join('');
    return `      <article class="pcard">
        <div class="band">
          <span><span class="nm">${p.name}</span><span class="tag">Featured Project</span></span>
          <span class="status"><i aria-hidden="true"></i>${p.status}</span>
        </div>
        <div class="inner">
          <h3>${p.tagline}</h3>
          <p class="desc">${p.description}</p>
          <div class="meta">${chips}</div>
          <div class="foot">
            <span class="muted" style="font-size:.9rem">项目详情页包含五个模块入口、演示账号与使用说明</span>
            <a class="btn solid" href="${p.url}">查看项目<span class="arrow" aria-hidden="true">→</span></a>
          </div>
        </div>
      </article>`;
  }).join('\n');
}

/**
 * @param {string} bio    one short paragraph for the footer
 * @param {string} links  pre-rendered navigation markup
 * @param {string} note   optional disclaimer sentence
 */
function siteFooter(bio, links, note) {
  const noteBlock = note ? '\n  <p class="disclaimer">' + note + '</p>' : '';
  return `<footer class="site-foot">
  <div class="top">
    <div class="bio">
      <span class="wordmark"><span class="sep" aria-hidden="true"></span>su46proj.site</span>
      <p>${bio}</p>
    </div>
    ${links}
  </div>${noteBlock}
  <div class="bottom">
    <span>© 2026 su46proj.site · 独立开发者作品</span>
    <span class="right">
      <a href="${AUTHOR_MAILTO}">${AUTHOR_EMAIL}</a>
      ${icpLink()}
    </span>
  </div>
</footer>`;
}

function footerLinksProject() {
  return `<nav class="fnav" aria-label="站点导航">
      <a href="https://ops.su46proj.site/" target="_blank" rel="noopener">运营端</a>
      <a href="https://merchant.su46proj.site/" target="_blank" rel="noopener">商户端</a>
      <a href="https://admin.su46proj.site/" target="_blank" rel="noopener">管理端</a>
      <a href="https://food.su46proj.site/" target="_blank" rel="noopener">外卖 H5</a>
      <a href="https://food-admin.su46proj.site/" target="_blank" rel="noopener">外卖后台</a>
      <a href="${SITE_URL}">作者主页</a>
    </nav>`;
}

function footerLinksPersonal() {
  return `<nav class="fnav" aria-label="站点导航">
      <a href="#about">01 关于我</a>
      <a href="#stack">02 技术栈</a>
      <a href="#projects">03 项目</a>
      <a href="#contact">04 联系方式</a>
      <a href="${PROJECT_URL}">MiniPay AI 项目页</a>
    </nav>`;
}

/* ------------------------------------------------------------------ */
/* personalHomePage() -- su46proj.site                                 */
/* ------------------------------------------------------------------ */

const HOME_CSS = `
.hero .meta-line{margin-top:clamp(30px,4vw,42px);display:flex;flex-wrap:wrap;gap:8px 26px;font-family:var(--mono);font-size:.78rem;letter-spacing:.05em;color:var(--muted)}
.hero .meta-line span{display:inline-flex;align-items:center;gap:9px}
.hero .meta-line i{width:5px;height:5px;background:var(--accent);display:inline-block;flex:0 0 auto}
`;

export function personalHomePage() {
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
${HEAD_META}
<title>苏启航 · 独立开发者 | su46proj.site</title>
<meta name="description" content="独立开发者苏启航的个人主页：支付与钱包系统、微服务与可靠性工程，以及正在维护的项目与联系方式。">
<meta name="theme-color" content="#faf9f7">
<meta name="robots" content="index, follow">
<style>${SHARED_CSS}${HOME_CSS}</style>
</head>
<body>

<header class="site-head">
  <div class="in">
    <a class="wordmark" href="#top"><span class="sep" aria-hidden="true"></span>su46proj.site</a>
    <nav class="nav" aria-label="主导航">
      <a href="#about"><span class="n">01</span>关于我</a>
      <a href="#stack"><span class="n">02</span>技术栈</a>
      <a href="#projects"><span class="n">03</span>项目</a>
      <a href="#contact"><span class="n">04</span>联系方式</a>
    </nav>
  </div>
</header>

<main id="top">

  <section class="hero col">
    <p class="status-pill"><i aria-hidden="true"></i>可合作 · 在线演示可用</p>
    <h1>苏启航 — 我做支付与钱包系统，也把它真正跑起来。</h1>
    <p class="lede">独立开发者，后端与全栈。一个人写服务、搭集群、做界面，目前主要在做 MiniPay AI：把支付、钱包和外卖业务放在同一套微服务里的演示平台。我关心的不是功能列表有多长，而是钱有没有算对、账能不能对上、服务挂掉之后能不能自己恢复。</p>
    <div class="acts">
      <a class="btn solid" href="${PROJECT_URL}">查看项目<span class="arrow" aria-hidden="true">→</span></a>
      <a class="btn" href="${AUTHOR_MAILTO}">联系我</a>
    </div>
    <div class="facts">
${renderFacts()}
    </div>
    <p class="meta-line">
      <span><i aria-hidden="true"></i>Java 21 / Spring Boot 3</span>
      <span><i aria-hidden="true"></i>K3s 私有集群</span>
      <span><i aria-hidden="true"></i>Cloudflare 边缘接入</span>
      <span><i aria-hidden="true"></i>支付 · 钱包 · 外卖</span>
    </p>
  </section>

  <section id="about" class="col">
${sectionHead('01', '关于我 / About', '我怎么写代码，以及我在意什么')}
    <div class="editorial">
      <aside class="aside">
        <p class="k">关注方向</p>
        <ul>
          <li><i aria-hidden="true"></i>支付编排与对账</li>
          <li><i aria-hidden="true"></i>钱包与复式账本</li>
          <li><i aria-hidden="true"></i>微服务边界划分</li>
          <li><i aria-hidden="true"></i>分布式一致性</li>
          <li><i aria-hidden="true"></i>边缘接入与部署</li>
        </ul>
      </aside>
      <div class="prose body">
        <p>我做的事情集中在资金相关的系统上：一笔支付从下单、扣款、回调到对账，中间会经过好几个服务，每一步都可能重复、超时或者失败。我喜欢把这类问题拆干净——谁拥有这份数据、状态机怎么流转、哪一步必须幂等、出错了从哪里补——然后再写代码。</p>
        <p>工程上我倾向于约束自己：服务之间只通过版本化接口和可靠事件协作，不跨库查询；余额和账本由一个服务独占写入，读写落在同一个本地事务里；金额统一用整数存分，不做浮点运算；纠错只允许冲正，不允许直接改数。这些规则听起来啰嗦，但它们决定了系统在出错的时候是可控的还是失控的。</p>
        <p>除了后端，我也会把整套东西自己部署起来：K3s 上跑服务与中间件，网关负责路由和 TLS，Cloudflare 放在最外层做 DNS、证书和静态内容分发。前端用 React + umi 写 B 端控制台，用 Jetpack Compose 写 Android 端。全链路自己走一遍，才知道哪些设计只是看起来合理。</p>
      </div>
    </div>
  </section>

  <section id="stack" class="col">
${sectionHead('02', '技术栈 / Stack', '日常在用的这些东西', '按环节分组，都是我在项目里实际写和运维的，不是清单式的罗列。')}
    <div class="groups">
${renderTechGroups()}
    </div>
  </section>

  <section id="projects" class="col">
${sectionHead('03', '项目 / Projects', '在做的东西', '下面每一项都是可以打开直接用的在线环境，不是停留在截图阶段的方案。这个列表由数据驱动，以后做完的项目会直接追加进来。')}
    <div class="plist">
${renderProjects()}
    </div>
  </section>

  <section id="contact" class="col">
${sectionHead('04', '联系方式 / Contact', '想聊技术或者合作，直接写邮件', '我基本每天都会看邮箱。聊架构、聊支付账务、聊部署都行，有具体问题的话带上背景会更快。')}
    <div class="ccard">
      <div class="ctop">
        <p>不管是项目里的实现细节、技术选型，还是想一起做点东西，都可以直接发邮件给我。工作日一般当天回，周末可能会慢一点。</p>
        <div class="mailrow">
          <a class="mail" href="${AUTHOR_MAILTO}">${AUTHOR_EMAIL}<span class="arrow" aria-hidden="true">→</span></a>
          <a class="btn" href="${PROJECT_URL}">先看看项目</a>
        </div>
      </div>
      <div class="facts2">
        <div class="cell"><p class="k">可以聊什么</p><p class="v">微服务边界划分、支付与账务设计、分布式一致性方案、K3s 私有部署、Cloudflare 边缘接入</p></div>
        <div class="cell"><p class="k">当前状态</p><p class="v">全职做自己的项目，接受技术交流与远程合作</p></div>
        <div class="cell"><p class="k">其他方式</p><p class="v">这个主页长期维护，新项目会直接更新在项目区</p></div>
      </div>
    </div>
  </section>

</main>

${siteFooter(
  '独立开发者的个人主页。目前在做 MiniPay AI，一个把支付、钱包与外卖业务放在同一套微服务里的演示平台。',
  footerLinksPersonal(),
  ''
)}
</body>
</html>`;
}

/* ------------------------------------------------------------------ */
/* projectLandingPage() -- pay.su46proj.site                           */
/* ------------------------------------------------------------------ */

const CAPABILITIES = [
  {
    no: '01',
    name: '身份与认证',
    text: '统一登录走 OAuth2 授权码 + PKCE，登录前有图形验证码，手机号以哈希形式存储。签发的令牌会校验签名、签发方、受众、过期时间与授权范围，校验不过就直接拒绝。',
    tags: ['OAuth2', 'PKCE', '图形验证码', '手机号哈希'],
  },
  {
    no: '02',
    name: '钱包与复式账本',
    text: '余额和账本由钱包服务独占写入，借贷分录在同一个本地事务里一起提交，不会出现余额动了、分录没落的情况。金额统一用人民币分的整数存储，写错了只能冲正，不能改数。',
    tags: ['复式记账', '同事务一致', '整数分', '冲正纠错'],
  },
  {
    no: '03',
    name: '支付编排 渠道 · 回调 · 对账',
    text: '把支付渠道、异步回调和日终对账收敛到一处：订单由状态机驱动，回调按幂等键去重，重复通知不会重复入账，超时和异常交给补偿任务收敛到终态。',
    tags: ['渠道编排', '异步回调', '幂等去重', '自动对账'],
  },
  {
    no: '04',
    name: '外卖业务 下单 · 支付 · 配送',
    text: '覆盖门店与菜品、下单、支付到配送的完整链路。外卖和支付是两个独立服务，各自持有自己的数据，通过事件与状态机对齐，外卖不会去直接改支付状态。',
    tags: ['门店菜品', '下单', '钱包支付', '配送调度'],
  },
  {
    no: '05',
    name: 'AI 智能助手 白名单工具调用',
    text: '助手只能调用事先登记好的工具接口，不直连业务数据库，也拿不到登录密码、支付密码、验证码或完整令牌。它做的是查询和编排，不是绕过权限去操作数据。',
    tags: ['工具白名单', '无数据库直连', '最小权限'],
  },
  {
    no: '06',
    name: '多端 B 端控制台',
    text: '运营端、商户端、管理端和外卖后台四套控制台，加上外卖 H5 与 Android App，分别对应平台方、商户方和消费者三个视角，看的是同一份数据。',
    tags: ['React', 'umi', '响应式', 'Android'],
  },
];

const ARCH_LADDER = [
  {
    name: '边缘接入',
    tag: 'CLOUDFLARE',
    text: 'Cloudflare 负责 DNS、TLS 证书与边缘缓存；这个 Worker 托管个人主页、本页和 APK 下载，其余流量按原样回源，不做改写。',
  },
  {
    name: '集群入口',
    tag: 'NGINX GATEWAY FABRIC',
    text: 'K3s 上的 Gateway API 实现统一收口：按域名与路径路由到对应服务，在入口做 TLS 终止、限流与灰度发布。',
  },
  {
    name: '微服务',
    tag: 'SPRING BOOT 3',
    text: '按领域水平拆分，每个服务独占自己的数据表，只通过版本化接口、可靠事件或事务分支协议协作，服务之间没有跨库查询。',
  },
  {
    name: '一致性',
    tag: 'SEATA / OUTBOX',
    text: '站内转账、钱包支付和内部冲正走短时 TCC；外卖订单、通道回调与开户走本地事务加 Outbox/Inbox 加幂等状态机，不用长事务。',
  },
  {
    name: '数据与中间件',
    tag: 'DATA',
    text: 'MySQL 存业务数据，Redis 承载缓存与令牌，RabbitMQ 传递领域事件，Seata 协调分布式事务，Cloudflare R2 存放 APK 与对象资源。',
  },
];

const ENTRIES = [
  { name: '运营端', host: 'ops.su46proj.site', url: 'https://ops.su46proj.site/', desc: '平台运营工作台' },
  { name: '商户端', host: 'merchant.su46proj.site', url: 'https://merchant.su46proj.site/', desc: '商户自助后台' },
  { name: '管理端', host: 'admin.su46proj.site', url: 'https://admin.su46proj.site/', desc: '系统管理后台' },
  { name: '外卖 H5', host: 'food.su46proj.site', url: 'https://food.su46proj.site/', desc: '消费者下单页' },
  { name: '外卖后台', host: 'food-admin.su46proj.site', url: 'https://food-admin.su46proj.site/', desc: '订单与配送管理' },
];

const ACCOUNTS = [
  { sys: '运营端', user: '13800138000', pw: 'MiniPay@123456', host: 'ops.su46proj.site', url: 'https://ops.su46proj.site/' },
  { sys: '商户端', user: '13900000009', pw: 'MiniPay@123456', host: 'merchant.su46proj.site', url: 'https://merchant.su46proj.site/' },
  { sys: '管理端', user: '13800138002', pw: 'MiniPay@123456', host: 'admin.su46proj.site', url: 'https://admin.su46proj.site/' },
  { sys: '外卖后台', user: 'admin', pw: 'admin123', host: 'food-admin.su46proj.site', url: 'https://food-admin.su46proj.site/' },
  { sys: '外卖 H5 / App', user: '任意演示手机号', pw: '123456', host: 'food.su46proj.site', url: 'https://food.su46proj.site/' },
];

const STEPS = [
  { t: '先进管理端看数据', d: '打开 admin.su46proj.site，用 13800138002 / MiniPay@123456 登录，翻一遍支付单、渠道配置和账本分录，先看清楚一笔钱在系统里长什么样。' },
  { t: '再对比运营端和商户端', d: '用 13800138000 登录 ops.su46proj.site、用 13900000009 登录 merchant.su46proj.site，同一个订单在平台视角和商户视角下的差别一眼就能看出来。' },
  { t: '去外卖 H5 下一单', d: '打开 food.su46proj.site，选门店、加购物车并下单，用验证码 123456 登录，再用钱包完成支付。' },
  { t: '回到外卖后台看订单流转', d: '用 admin / admin123 登录 food-admin.su46proj.site，找到刚才那笔订单，走一遍接单、配送、完成的状态流转。' },
  { t: '装 App 看移动端', d: '下载并安装 Android App，用任意演示手机号加验证码 123456 登录，查看钱包余额、账单明细和外卖入口。' },
  { t: '试一下 AI 智能助手', d: '在助手对话里查询订单或余额，观察它是通过工具接口取数，而不是直接访问数据库。' },
];

const LANDING_CSS = `
.hero .toptags{display:flex;flex-wrap:wrap;gap:8px;margin-top:clamp(26px,3.4vw,38px)}
.factbar{display:grid;grid-template-columns:repeat(auto-fit,minmax(184px,1fr));gap:1px;background:var(--rule);border:var(--hair);border-radius:var(--radius);overflow:hidden;box-shadow:var(--shadow-1);margin-top:clamp(28px,3.6vw,40px)}
.factbar .f{background:var(--card);padding:clamp(18px,2.4vw,24px)}
.factbar .f .k{font-family:var(--mono);font-size:.72rem;letter-spacing:.13em;text-transform:uppercase;color:var(--muted)}
.factbar .f .v{margin-top:8px;color:var(--ink);font-weight:600;font-size:1rem}
.factbar .f .v small{display:block;font-weight:400;font-size:.82rem;color:var(--muted);margin-top:5px;letter-spacing:0}
@media (max-width:720px){.factbar{grid-template-columns:repeat(2,minmax(0,1fr))}}
`;

function renderCapabilities() {
  return CAPABILITIES.map(function (c) {
    const tags = c.tags
      .map(function (t) {
        return '<span class="chip">' + t + '</span>';
      })
      .join('');
    return `      <article class="cell-card">
        <p class="no">${c.no}</p>
        <h3>${c.name}</h3>
        <p>${c.text}</p>
        <div class="chips">${tags}</div>
      </article>`;
  }).join('\n');
}

function renderLadder() {
  return ARCH_LADDER.map(function (r) {
    return `      <div class="rung">
        <p class="lname">${r.name}<span>${r.tag}</span></p>
        <p class="ldesc">${r.text}</p>
      </div>`;
  }).join('\n');
}

function renderEntries() {
  return ENTRIES.map(function (e) {
    return `      <a class="ecard" href="${e.url}" target="_blank" rel="noopener">
        <span class="etop"><span class="nm">${e.name}</span><span class="arrow" aria-hidden="true">↗</span></span>
        <span class="desc">${e.desc}</span>
        <span class="host">${e.host}</span>
      </a>`;
  }).join('\n');
}

function renderAccounts() {
  return ACCOUNTS.map(function (a) {
    return `        <tr>
          <td class="sys">${a.sys}</td>
          <td><code>${a.user}</code></td>
          <td><code>${a.pw}</code></td>
          <td><a href="${a.url}" target="_blank" rel="noopener">${a.host}</a></td>
        </tr>`;
  }).join('\n');
}

function renderSteps() {
  return STEPS.map(function (s) {
    return `      <li><div><b>${s.t}</b><p>${s.d}</p></div></li>`;
  }).join('\n');
}

export function projectLandingPage() {
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
${HEAD_META}
<title>MiniPay AI · 数字支付 / 钱包 / 外卖一体化演示平台</title>
<meta name="description" content="MiniPay AI 是一个数字支付、钱包与外卖一体化的演示平台：OAuth2 + PKCE 统一认证、复式账本钱包、支付编排与对账、外卖配送链路与 AI 智能助手，全部在线可体验。">
<meta name="theme-color" content="#faf9f7">
<meta name="robots" content="index, follow">
<style>${SHARED_CSS}${LANDING_CSS}</style>
</head>
<body>

<header class="site-head">
  <div class="in">
    <a class="wordmark" href="#top"><span class="sep" aria-hidden="true"></span>MiniPay AI</a>
    <nav class="nav" aria-label="主导航">
      <a href="#caps"><span class="n">01</span>核心能力</a>
      <a href="#arch"><span class="n">02</span>架构一览</a>
      <a href="#entries"><span class="n">03</span>模块入口</a>
      <a href="#accounts"><span class="n">04</span>演示账号</a>
      <a href="#howto"><span class="n">05</span>使用说明</a>
      <a class="out" href="${SITE_URL}">作者主页</a>
    </nav>
  </div>
</header>

<main id="top">

  <section class="hero col">
    <p class="status-pill"><i aria-hidden="true"></i>在线演示环境 · 可自由体验</p>
    <h1>MiniPay AI</h1>
    <p class="lede">数字支付 / 钱包 / 外卖一体化演示平台。统一身份认证、带复式账本的钱包、支付渠道编排与对账、外卖下单到配送的完整链路，跑在同一套 K3s 私有集群上，另外带一个只能调用白名单工具的 AI 助手。</p>
    <div class="acts">
      <a class="btn solid" href="https://admin.su46proj.site/" target="_blank" rel="noopener">进入管理端<span class="arrow" aria-hidden="true">↗</span></a>
      <a class="btn" href="https://ops.su46proj.site/" target="_blank" rel="noopener">进入运营端</a>
      <a class="btn" href="${APK_URL}" target="_blank" rel="noopener">下载 Android App</a>
    </div>
    <div class="toptags">
      <span class="chip">OAuth2 + PKCE</span>
      <span class="chip">复式账本</span>
      <span class="chip">K3s 私有部署</span>
      <span class="chip">Spring Boot 3</span>
      <span class="chip">Android App</span>
    </div>
    <div class="factbar">
      <div class="f"><p class="k">模块入口</p><p class="v">5 个<small>运营端 / 商户端 / 管理端 / 外卖 H5 / 外卖后台</small></p></div>
      <div class="f"><p class="k">认证方式</p><p class="v">OAuth2 + PKCE<small>图形验证码，手机号哈希存储</small></p></div>
      <div class="f"><p class="k">账务模型</p><p class="v">复式记账<small>金额以人民币分的整数存储</small></p></div>
      <div class="f"><p class="k">部署形态</p><p class="v">单套 K3s 集群<small>Cloudflare 边缘接入与回源</small></p></div>
    </div>
  </section>

  <section id="caps" class="wide">
${sectionHead('01', '核心能力', '六个能真的点开、跑起来的模块', '下面每一项都在演示环境里真实运行，登录对应控制台就能看到数据流转，不是示意图。')}
    <div class="grid g3">
${renderCapabilities()}
    </div>
  </section>

  <section id="arch" class="col">
${sectionHead('02', '架构一览', '一个请求从浏览器到数据库，会经过哪几层', '按流量方向从外到内说，所有组件都部署在同一个 K3s 集群里。')}
    <div class="ladder">
${renderLadder()}
    </div>
    <p class="note-line">外卖和支付是两个独立服务：外卖不能直接改支付状态，支付也不能改外卖订单，两边靠事件和状态机对齐。这条约束看起来多余，但它决定了出问题时能不能查清楚。</p>
  </section>

  <section id="entries" class="col">
${sectionHead('03', '模块入口', '五个可以打开的演示入口', '都在同一个演示环境内，账号通用、数据互通，点开就是真实页面。')}
    <div class="entries">
${renderEntries()}
    </div>
  </section>

  <section id="accounts" class="col">
${sectionHead('04', '演示账号', '登录用的账号和密码', '全部是演示数据。请不要修改密码，也不要填写任何真实的个人信息。')}
    <div class="tbl-wrap">
      <table>
        <caption class="sr">演示账号一览</caption>
        <thead>
          <tr><th scope="col">系统</th><th scope="col">账号</th><th scope="col">密码 / 验证码</th><th scope="col">入口</th></tr>
        </thead>
        <tbody>
${renderAccounts()}
        </tbody>
      </table>
    </div>
    <p class="note-line">App 登录和外卖 H5 的短信验证码固定为 <code>123456</code>，手机号填任意演示号码即可。演示环境的数据可能会定期重置。</p>
  </section>

  <section id="howto" class="col">
${sectionHead('05', '使用说明', '按这个顺序走一遍，十来分钟能看完主链路', '从后台数据看到用户下单支付，再到订单流转，整条链路的每一步都有对应的入口。')}
    <ol class="steps">
${renderSteps()}
    </ol>
  </section>

  <section class="col">
    <div class="band2">
      <div class="t">
        <h3>Android App 下载</h3>
        <p>用户在手机上的完整体验：钱包余额、支付、账单明细、外卖下单入口，用短信验证码登录。安装包直接取自对象存储，不带任何跳转页。</p>
        <p class="f">${APK_URL}</p>
      </div>
      <a class="btn solid" href="${APK_URL}" target="_blank" rel="noopener">下载 APK</a>
    </div>
    <div class="notice" style="margin-top:26px">
      <span class="mark" aria-hidden="true"></span>
      <p>演示环境说明：本站及所有子站都是技术演示（demo），账号、余额、订单与交易数据均为模拟生成的示例数据，不构成任何真实的支付、金融或资金服务，请不要向任何演示账号充值或转账。</p>
    </div>
    <div class="callout-home" style="margin-top:34px">
      <p>这个平台由苏启航独立设计与实现，个人主页上有关于我、技术栈和其他项目的说明。</p>
      <a class="btn" href="${SITE_URL}">访问作者主页 su46proj.site<span class="arrow" aria-hidden="true">→</span></a>
    </div>
  </section>

</main>

${siteFooter(
  'MiniPay AI —— 数字支付、钱包与外卖一体化的演示平台，由独立开发者苏启航设计与实现。',
  footerLinksProject(),
  '免责声明：本站及其子站均为技术演示环境，所有账号、余额、订单与交易数据均为模拟示例数据，不构成任何真实的金融服务或资金承诺。'
)}
</body>
</html>`;
}
