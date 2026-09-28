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

const routeFilters = [
  ['/operate/billing/index', ['今日订单']],
  ['/operate/member/storeMember/content', ['持卡', '未持卡', '无等级', '30天内未消费', '60天内未消费', '90天内未消费', '1次及以内', '3次及以内', '5次及以内', '今天', '未来3天', '未来7天']],
  ['/operate/orderForGoods/orderList/content', ['项目', '产品', '开卡', '卡充值', '卡换卡', '退单', '退卡', '跨店消费', '补录单', '美团团购核销', '营销活动核销', '补款单', '抖音团购核销', '尾款还清', '退部分单', '实收类', '卡耗类', '非实收非卡耗类', '卡买卡', '欠款', '钱包', '支付宝', 'POS', '收钱吧', '赠送', '营销活动', '积分抵现', '通用会员权益', '抖音团购', '美团团购', '现金', '卡赠送权益', '减免', '会员卡', '赠品核销', '赠送消费金', '微信', '券核销', '营销内容核销', '已核对', '未核对']],
  ['/operate/yuji/business/index', ['日维度', '月维度']],
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
  const nums = entries.filter((entry) => entry.isFile() && entry.name.toLowerCase().endsWith('.png'))
    .map((entry) => Number.parseInt(entry.name, 10)).filter(Number.isFinite);
  return String((nums.length ? Math.max(...nums) : 0) + 1).padStart(3, '0');
}

async function capture(page, route, label) {
  const folder = path.join(OUTPUT, moduleName(page.url() || route));
  await ensure(folder);
  const number = await nextScreenshot(folder);
  const file = path.join(folder, `${number}_筛选_${safeName(label)}.png`);
  await page.screenshot({ path: file, fullPage: true, animations: 'disabled' });
  return { route: routeKey(page.url() || route), module: moduleName(page.url() || route), label, file };
}

async function findFilter(page, label) {
  const id = `filter-capture-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  const found = await page.evaluate(({ label, id }) => {
    const candidates = [...document.querySelectorAll('.tab-item.no-warp')];
    const element = candidates.find((candidate) => {
      const text = (candidate.innerText || '').replace(/\s+/g, ' ').trim();
      const rect = candidate.getBoundingClientRect();
      const style = getComputedStyle(candidate);
      return text === label && rect.x > 210 && rect.width > 0 && rect.height > 0
        && style.display !== 'none' && style.visibility !== 'hidden';
    });
    if (!element) return false;
    element.setAttribute('data-wikemi-filter-capture', id);
    return true;
  }, { label, id });
  return found ? page.locator(`[data-wikemi-filter-capture="${id}"]`).first() : null;
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
  for (const [routePath, labels] of routeFilters) {
    const route = `${BASE}${routePath}`;
    for (const label of [...new Set(labels)]) {
      try {
        await page.goto(route, { waitUntil: 'domcontentloaded', timeout: 35000 });
        await wait(page);
        const filter = await findFilter(page, label);
        if (!filter || !(await filter.count())) {
          skipped.push({ route: routeKey(route), label, reason: 'not-found' });
          continue;
        }
        await filter.scrollIntoViewIfNeeded({ timeout: 5000 });
        await filter.click({ timeout: 6000 });
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
