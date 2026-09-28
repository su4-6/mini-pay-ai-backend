import assert from 'node:assert/strict';
import test from 'node:test';

import { personalHomePage, projectLandingPage } from '../src/pages.js';

const RETIRED_PUBLIC_TERMS = [
  '外卖',
  'food.su46proj.site',
  'food-admin.su46proj.site',
  'Android',
  '.apk',
  'download.su46proj.site',
];

test('public pages do not advertise retired consumer surfaces', () => {
  for (const html of [personalHomePage(), projectLandingPage()]) {
    assert.match(html, /rel="icon" href="data:image\/svg\+xml/);
    for (const term of RETIRED_PUBLIC_TERMS) {
      assert.equal(html.includes(term), false, `unexpected public reference: ${term}`);
    }
  }
});

test('product page presents the current four web entrances', () => {
  const html = projectLandingPage();
  const entryCards = html.match(/class="ecard"/g) ?? [];

  assert.equal(entryCards.length, 4);
  assert.match(html, /消费者 H5 \/ 运营端 \/ 商户端 \/ 管理端/);
  for (const host of [
    'app.su46proj.site',
    'ops.su46proj.site',
    'merchant.su46proj.site',
    'admin.su46proj.site',
  ]) {
    assert.match(html, new RegExp(host.replaceAll('.', '\\.')));
  }
});

test('personal homepage links to the same four-entry product scope', () => {
  const html = personalHomePage();

  assert.match(html, /项目详情页包含四个在线入口/);
  assert.doesNotMatch(html, /五个模块入口|六个模块入口/);
});

test('product page describes the H5 financial and AI flows accurately', () => {
  const html = projectLandingPage();

  assert.match(html, /收付款、转账、银行卡、充值提现/);
  assert.match(html, /最终仍由用户在钱包页确认/);
  assert.match(html, /账号和权限并不通用/);
  assert.match(html, /所有金额都是沙箱数据/);
  assert.match(html, /个人主页和项目页由 K3s 静态服务提供/);
  assert.doesNotMatch(html, /对象存储承载头像/);
});
