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

const controls = [
  ['/operate/orderForGoods/orderList/content', ['POS', '营销内容核销', '已核对', '未核对']],
  ['/operate/yuji/business/index', ['日维度', '月维度']],
  ['/operate/inventory/management', ['期初期末库存查询', '库存设置']],
  ['/operate/inventory/detail', ['出库记录']],
  ['/operate/inventory/call', ['调拨出库管理']],
  ['/operate/inventory/account', ['成本调整', '成本调整记录']],
];
const safeName = (value) => (String(value || '未命名').replace(/[<>:"/\\|?*\x00-\x1f]/g, '_').replace(/\s+/g, ' ').trim() || '未命名').slice(0, 80);
const moduleName = (url) => safeName(new URL(url, BASE).pathname.split('/').filter(Boolean).join('_') || 'home');
const routeKey = (url) => { const u = new URL(url, BASE); return `${u.pathname}${u.search}`; };
const wait = async (page) => { await page.waitForLoadState('domcontentloaded', { timeout: 30000 }).catch(() => {}); await page.waitForTimeout(1600); await page.waitForLoadState('networkidle', { timeout: 4500 }).catch(() => {}); };
const ensure = (dir) => fs.mkdir(dir, { recursive: true });

async function nextNumber(folder) {
  const entries = await fs.readdir(folder, { withFileTypes: true }).catch(() => []);
  const numbers = entries.filter((entry) => entry.isFile() && entry.name.endsWith('.png')).map((entry) => Number.parseInt(entry.name, 10)).filter(Number.isFinite);
  return String((numbers.length ? Math.max(...numbers) : 0) + 1).padStart(3, '0');
}

async function findControl(page, label) {
  const id = `retry-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  const found = await page.evaluate(({ label, id }) => {
    const candidates = [...document.querySelectorAll('.tab-item.no-warp')];
    const el = candidates.find((node) => {
      const rect = node.getBoundingClientRect();
      const style = getComputedStyle(node);
      return (node.innerText || '').replace(/\s+/g, ' ').trim() === label && rect.x > 210 && rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden';
    });
    if (!el) return false;
    el.setAttribute('data-wikemi-retry-control', id);
    return true;
  }, { label, id });
  return found ? page.locator(`[data-wikemi-retry-control="${id}"]`).first() : null;
}

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: CHROME, args: ['--disable-gpu', '--no-sandbox'] });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 1, extraHTTPHeaders: { Authorization: `Bearer ${TOKEN}`, 'Cache-Control': 'no-cache' } });
  await context.addCookies([{ name: 'Token', value: TOKEN, domain: 'saas.wikemi.com', path: '/', secure: true }, { name: 'LinkState', value: '1', domain: 'saas.wikemi.com', path: '/', secure: true }]);
  const page = await context.newPage();
  const captured = []; const skipped = [];
  for (const [routePath, labels] of controls) {
    for (const label of labels) {
      let done = false; let lastError = '';
      for (let attempt = 1; attempt <= 3 && !done; attempt++) {
        try {
          await page.goto(`${BASE}${routePath}`, { waitUntil: 'domcontentloaded', timeout: 35000 });
          await wait(page);
          const control = await findControl(page, label);
          if (!control || !(await control.count())) { lastError = 'not-found'; continue; }
          await control.scrollIntoViewIfNeeded({ timeout: 5000 });
          await control.click({ timeout: 6000 });
          await page.waitForTimeout(900);
          await page.waitForLoadState('networkidle', { timeout: 2500 }).catch(() => {});
          const folder = path.join(OUTPUT, moduleName(page.url() || routePath)); await ensure(folder);
          const number = await nextNumber(folder);
          const file = path.join(folder, `${number}_补充筛选_${safeName(label)}.png`);
          await page.screenshot({ path: file, fullPage: true, animations: 'disabled' });
          captured.push({ route: routeKey(page.url() || routePath), label, file, attempt }); done = true;
        } catch (error) { lastError = String(error.message || error); await page.waitForTimeout(1200); }
      }
      if (!done) skipped.push({ route: routeKey(`${BASE}${routePath}`), label, reason: lastError });
    }
  }
  await browser.close();
  console.log(JSON.stringify({ output: OUTPUT, captured: captured.length, skipped }, null, 2));
}

main().catch((error) => { console.error(error.stack || error); process.exitCode = 1; });
