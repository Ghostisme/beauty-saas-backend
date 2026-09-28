import { createRequire } from 'node:module';
import fs from 'node:fs/promises';
import path from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const TOKEN = process.env.WIKEMI_TOKEN;
if (!TOKEN) throw new Error('WIKEMI_TOKEN is required');

const BASE = 'https://saas.wikemi.com';
const OUTPUT = process.env.WIKEMI_OUTPUT || 'C:/Users/Administrator/Desktop/峰的saas/2026-09-26_全量截图';
const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';

// These are content-area tabs observed on the authenticated pages. Actions such
// as export, recharge, create, and save are deliberately excluded.
const routeTabs = [
  ['/operate/billing/index', ['今日订单']],
  ['/operate/member/storeMember/content', ['高级查询', '顾客寄存', '顾客回访', '顾客跟进']],
  ['/operate/orderForGoods/orderList/content', ['未完成订单列表', '尾款单', '项目', '产品', '实收类', '卡耗类', '未核对']],
  ['/operate/yuji/business/index', ['新客数据表', '美容师统计表', '客户盘点表', '品项卡报表', '负债报表', '报表中心']],
  ['/operate/systemRecord/orderOperation/index?startTime=2026-09-01&endTime=2026-09-26&type=setting', ['顾客操作', '顾客会员卡操作', '品项操作', '員工操作', '员工操作', '其他操作日志']],
  ['/operate/bookkeeping/expenditure/content?startTime=2026-09-01&endTime=2026-09-26&type=2&categoryIds=', ['其他收入记录']],
  ['/operate/target/list/index', ['員工行动计划', '员工行动计划']],
  ['/message/info/setting/index', ['短信发送记录', '短信余额充值']],
  ['/operate/inventory/management', ['期初期末库存查询', '库存设置']],
  ['/operate/inventory/detail', ['出库记录']],
  ['/operate/inventory/call', ['调拨出库管理']],
  ['/operate/inventory/account', ['成本调整', '成本调整记录']],
];

const safeName = (value, fallback = '未命名') => (String(value || fallback)
  .replace(/[<>:"/\\|?*\x00-\x1f]/g, '_').replace(/\s+/g, ' ').trim() || fallback).slice(0, 80);
const moduleName = (url) => safeName(new URL(url, BASE).pathname.split('/').filter(Boolean).join('_') || 'home');
const routeKey = (url) => { const u = new URL(url, BASE); return `${u.pathname}${u.search}`; };
const wait = async (page) => {
  await page.waitForLoadState('domcontentloaded', { timeout: 30000 }).catch(() => {});
  await page.waitForTimeout(1000);
  await page.waitForLoadState('networkidle', { timeout: 4500 }).catch(() => {});
};
const ensure = (dir) => fs.mkdir(dir, { recursive: true });

async function nextScreenshot(folder) {
  const entries = await fs.readdir(folder, { withFileTypes: true }).catch(() => []);
  const nums = entries
    .filter((entry) => entry.isFile() && entry.name.toLowerCase().endsWith('.png'))
    .map((entry) => Number.parseInt(entry.name, 10))
    .filter(Number.isFinite);
  return String((nums.length ? Math.max(...nums) : 0) + 1).padStart(3, '0');
}

async function capture(page, route, label) {
  const folder = path.join(OUTPUT, moduleName(page.url() || route));
  await ensure(folder);
  const number = await nextScreenshot(folder);
  const file = path.join(folder, `${number}_页签_${safeName(label)}.png`);
  await page.screenshot({ path: file, fullPage: true, animations: 'disabled' });
  return { route: routeKey(page.url() || route), module: moduleName(page.url() || route), label, file };
}

async function findContentTab(page, label) {
  const id = `tab-capture-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  const found = await page.evaluate(({ label, id }) => {
    const candidates = [...document.querySelectorAll('.tab-item.tab-item_pc,[role="tab"],.ant-tabs-tab')];
    const element = candidates.find((candidate) => {
      const text = (candidate.innerText || candidate.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim();
      const rect = candidate.getBoundingClientRect();
      const style = getComputedStyle(candidate);
      return text === label && rect.x > 210 && rect.width > 0 && rect.height > 0
        && style.display !== 'none' && style.visibility !== 'hidden';
    });
    if (!element) return false;
    element.setAttribute('data-wikemi-tab-capture', id);
    return true;
  }, { label, id });
  return found ? page.locator(`[data-wikemi-tab-capture="${id}"]`).first() : null;
}

async function main() {
  await ensure(OUTPUT);
  const browser = await chromium.launch({ headless: true, executablePath: CHROME, args: ['--disable-gpu', '--no-sandbox'] });
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    deviceScaleFactor: 1,
    extraHTTPHeaders: { Authorization: `Bearer ${TOKEN}`, 'Cache-Control': 'no-cache' },
  });
  await context.addCookies([
    { name: 'Token', value: TOKEN, domain: 'saas.wikemi.com', path: '/', secure: true },
    { name: 'LinkState', value: '1', domain: 'saas.wikemi.com', path: '/', secure: true },
  ]);
  const page = await context.newPage();
  const captured = [];
  const skipped = [];
  for (const [routePath, labels] of routeTabs) {
    const route = `${BASE}${routePath}`;
    for (const label of [...new Set(labels)]) {
      try {
        await page.goto(route, { waitUntil: 'domcontentloaded', timeout: 35000 });
        await wait(page);
        const tab = await findContentTab(page, label);
        if (!tab || !(await tab.count())) {
          skipped.push({ route: routeKey(route), label, reason: 'not-found' });
          continue;
        }
        await tab.scrollIntoViewIfNeeded({ timeout: 5000 });
        await tab.click({ timeout: 6000 });
        await page.waitForTimeout(500);
        await page.waitForLoadState('networkidle', { timeout: 2500 }).catch(() => {});
        captured.push(await capture(page, route, label));
      } catch (error) {
        skipped.push({ route: routeKey(route), label, reason: String(error.message || error) });
      }
    }
  }
  await browser.close();
  console.log(JSON.stringify({ output: OUTPUT, captured: captured.length, skipped }, null, 2));
}

main().catch((error) => { console.error(error.stack || error); process.exitCode = 1; });
