import { createRequire } from 'node:module';
import fs from 'node:fs/promises';
import path from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const TOKEN = process.env.WIKEMI_TOKEN;
if (!TOKEN) throw new Error('WIKEMI_TOKEN is required');
const BASE = 'https://saas.wikemi.com';
const OUTPUT = process.env.WIKEMI_OUTPUT || 'C:/Users/Administrator/Desktop/峰的saas/2026-09-26_已确认模块';
const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const routes = [
  ['/home', '首页'],
  ['/operate/appointment', '预约'],
  ['/operate/billing/index', '开单'],
  ['/operate/member/storeMember/content', '顾客'],
  ['/operate/orderForGoods/orderList/content', '订单'],
  ['/operate/yuji/business/index', '数据报表'],
  ['/operate/systemRecord/orderOperation/index?startTime=2026-09-01&endTime=2026-09-26&type=setting', '系统日志'],
  ['/operate/bookkeeping/expenditure/content?startTime=2026-09-01&endTime=2026-09-26&type=2&categoryIds=', '收支'],
  ['/operate/target/list/index', '目标'],
  ['/message/info/setting/index', '短信'],
  ['/marketing/tool/index', '营销工具'],
  ['/operate/inventory/management', '库存管理'],
  ['/operate/inventory/batch', '批次管理'],
  ['/operate/inventory/detail', '出入明细'],
  ['/operate/inventory/liquidation', '库存盘点'],
  ['/operate/inventory/call', '调拨管理'],
  ['/operate/inventory/account', '成本核算'],
];
const state = { startedAt: new Date().toISOString(), output: OUTPUT, routes: [], screenshots: [], errors: [], skipped: [] };
const skip = /删除|作废|退款|结算|支付|充值|保存|提交|确认|确定|退出|注销|登出|清空|批量|导出|导入|发短信|发送|核销|开单|下单|开卡|续卡|退卡|移除|解绑|新建|新增|创建|立即使用|查看示例|下载|设置基础|重置/i;
const useful = /筛选|查询|搜索|日期|时间|门店|品牌|分类|类型|状态|全部|日维度|月维度|标签|详情|更多|展开|收起|上一页|下一页|分页|列表|报表|维度|今天|近7天|近30天|自定义|tab|明细|记录|账户|顾客|产品|库存|余额|提醒|暂未设置|切换/i;

const safeName = (value, fallback = '未命名') => (String(value || fallback).replace(/[<>:"/\\|?*\x00-\x1f]/g, '_').replace(/\s+/g, ' ').trim() || fallback).slice(0, 80);
const routeKey = (url) => { const u = new URL(url, BASE); return `${u.pathname}${u.search}`; };
const moduleName = (url) => safeName(new URL(url, BASE).pathname.split('/').filter(Boolean).join('_') || 'home');
const wait = async (page) => { await page.waitForLoadState('domcontentloaded', { timeout: 30000 }).catch(() => {}); await page.waitForTimeout(1100); await page.waitForLoadState('networkidle', { timeout: 5000 }).catch(() => {}); };
const ensure = (dir) => fs.mkdir(dir, { recursive: true });

async function shot(page, route, name, note) {
  const folder = path.join(OUTPUT, moduleName(page.url() || route)); await ensure(folder);
  const count = state.screenshots.filter((x) => x.module === moduleName(page.url() || route)).length + 1;
  const file = path.join(folder, `${String(count).padStart(3, '0')}_${safeName(name)}.png`);
  await page.screenshot({ path: file, fullPage: true, animations: 'disabled' });
  state.screenshots.push({ module: moduleName(page.url() || route), route: routeKey(page.url() || route), file, note });
}

async function descriptors(page) {
  return page.evaluate(() => {
    let index = 0;
    return [...document.querySelectorAll('button,a,[role="button"],[role="tab"],[role="menuitem"],input,select,textarea,.ant-select-selection,.ant-calendar-picker,.ant-pagination-item,.ant-pagination-prev,.ant-pagination-next')].map((el) => {
      const r = el.getBoundingClientRect(), s = getComputedStyle(el);
      if (!r.width || !r.height || s.display === 'none' || s.visibility === 'hidden') return null;
      const id = `capture-${index++}`; el.setAttribute('data-capture-id', id);
      return { id, tag: el.tagName.toLowerCase(), role: el.getAttribute('role') || '', text: (el.innerText || el.getAttribute('aria-label') || el.getAttribute('title') || el.getAttribute('placeholder') || '').replace(/\s+/g, ' ').trim().slice(0, 100), cls: String(el.className || '').slice(0, 100) };
    }).filter(Boolean);
  });
}

async function interactPage(page, route, moduleLabel) {
  const items = await descriptors(page);
  const seen = new Set(); let count = 0;
  for (const item of items) {
    if (count >= 12 || seen.has(item.text) || !item.text || skip.test(item.text) || item.role === 'menuitem' || /ant-menu/.test(item.cls) || (!useful.test(item.text) && item.tag !== 'input' && item.tag !== 'select')) continue;
    seen.add(item.text);
    const loc = page.locator(`[data-capture-id="${item.id}"]`).first(); if (!(await loc.count())) continue;
    try {
      await loc.scrollIntoViewIfNeeded({ timeout: 2000 }); if (!(await loc.isVisible())) continue;
      const before = page.url();
      if (item.tag === 'input' || item.tag === 'textarea') await loc.focus({ timeout: 2500 });
      else await loc.click({ timeout: 4500 });
      await page.waitForTimeout(400); await page.waitForLoadState('networkidle', { timeout: 1800 }).catch(() => {});
      await shot(page, route, `交互_${moduleLabel}_${item.text}`, `tag:${item.tag};role:${item.role};class:${item.cls}`); count++;
      await page.keyboard.press('Escape').catch(() => {});
      if (page.url() !== before && page.url() !== route) { await page.goto(route, { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => {}); await wait(page); }
    } catch (e) { state.errors.push({ route: routeKey(page.url() || route), control: item.text, error: String(e.message || e) }); }
  }
}

async function main() {
  await ensure(OUTPUT);
  const browser = await chromium.launch({ headless: true, executablePath: CHROME, args: ['--disable-gpu', '--no-sandbox'] });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 1, extraHTTPHeaders: { Authorization: `Bearer ${TOKEN}`, 'Cache-Control': 'no-cache' } });
  await context.addCookies([{ name: 'Token', value: TOKEN, domain: 'saas.wikemi.com', path: '/', secure: true }, { name: 'LinkState', value: '1', domain: 'saas.wikemi.com', path: '/', secure: true }]);
  const page = await context.newPage(); page.on('pageerror', (e) => state.errors.push({ route: routeKey(page.url()), pageerror: String(e.message || e) }));
  for (const [routePath, label] of routes) {
    const route = `${BASE}${routePath}`; const record = { route: routeKey(route), label, status: null, finalUrl: null, title: null, bodySample: null };
    try {
      const response = await page.goto(route, { waitUntil: 'domcontentloaded', timeout: 35000 }); record.status = response?.status() ?? null; await wait(page); record.finalUrl = page.url(); record.title = await page.title(); record.bodySample = (await page.locator('body').innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 800); await shot(page, route, '页面', `status:${record.status};title:${record.title}`); await interactPage(page, route, label); record.screenshotCount = state.screenshots.filter((s) => s.route === routeKey(page.url())).length;
    } catch (e) { record.error = String(e.stack || e); state.errors.push({ route: record.route, error: record.error }); }
    state.routes.push(record); await fs.writeFile(path.join(OUTPUT, 'manifest.json'), JSON.stringify(state, null, 2), 'utf8');
  }
  state.finishedAt = new Date().toISOString(); state.authenticatedHeuristic = state.routes.every((r) => !/\/login/i.test(r.finalUrl || '') && !/登录|请先登录|重新登录/.test(r.bodySample || '')); await fs.writeFile(path.join(OUTPUT, 'manifest.json'), JSON.stringify(state, null, 2), 'utf8'); await browser.close(); console.log(JSON.stringify({ output: OUTPUT, routes: state.routes.length, screenshots: state.screenshots.length, errors: state.errors.length, authenticatedHeuristic: state.authenticatedHeuristic }, null, 2));
}
main().catch(async (e) => { state.fatal = String(e.stack || e); await ensure(OUTPUT).catch(() => {}); await fs.writeFile(path.join(OUTPUT, 'manifest.json'), JSON.stringify(state, null, 2), 'utf8').catch(() => {}); console.error(e.stack || e); process.exitCode = 1; });
