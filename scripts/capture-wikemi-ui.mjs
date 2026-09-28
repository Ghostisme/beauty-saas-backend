import { createRequire } from 'node:module';
import fs from 'node:fs/promises';
import path from 'node:path';

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

const TOKEN = process.env.WIKEMI_TOKEN;
if (!TOKEN) throw new Error('WIKEMI_TOKEN is required');

const BASE = 'https://saas.wikemi.com';
const START = `${BASE}/operate/appointment`;
const OUTPUT = process.env.WIKEMI_OUTPUT || 'C:/Users/Administrator/Desktop/峰的saas/2026-09-26_自动采集';
const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const MAX_ROUTES = Number(process.env.WIKEMI_MAX_ROUTES || 80);
const MAX_CONTROLS = Number(process.env.WIKEMI_MAX_CONTROLS || 24);

const dangerous = /删除|作废|退款|结算|支付|充值|保存|提交|确认|确定|退出|注销|登出|清空|批量|导出|导入|发短信|发送|核销|开单|下单|开卡|续卡|退卡|移除|解绑|新建|新增|创建|删除|关闭窗口/i;
const interactive = /筛选|过滤|搜索|查询|更多|展开|收起|全部|日期|今天|昨日|日|周|月|年|列表|看板|设置|选择|下拉|刷新|排序|上一页|下一页|详情|查看|返回|切换|预约/i;

const state = {
  startedAt: new Date().toISOString(),
  start: START,
  output: OUTPUT,
  routes: [],
  screenshots: [],
  errors: [],
};

function safeName(value, fallback = '未命名') {
  const text = String(value || fallback).replace(/[<>:"/\\|?*\x00-\x1f]/g, '_').replace(/\s+/g, ' ').trim();
  return (text || fallback).slice(0, 90);
}

function routeKey(raw) {
  const url = new URL(raw, BASE);
  return `${url.pathname}${url.search}`;
}

function moduleName(raw) {
  const url = new URL(raw, BASE);
  const pieces = url.pathname.split('/').filter(Boolean);
  return safeName(pieces.length ? pieces.join('_') : '首页');
}

async function ensureDir(dir) {
  await fs.mkdir(dir, { recursive: true });
}

async function waitForApp(page) {
  await page.waitForLoadState('domcontentloaded', { timeout: 30000 }).catch(() => {});
  await page.waitForTimeout(1200);
  await page.waitForLoadState('networkidle', { timeout: 8000 }).catch(() => {});
  await page.waitForTimeout(500);
}

async function capture(page, rawUrl, name, note = '') {
  const url = page.url() || rawUrl;
  const folder = path.join(OUTPUT, moduleName(url));
  await ensureDir(folder);
  const fileName = `${String(state.screenshots.filter((x) => x.module === moduleName(url)).length + 1).padStart(3, '0')}_${safeName(name)}.png`;
  const filePath = path.join(folder, fileName);
  await page.screenshot({ path: filePath, fullPage: true, animations: 'disabled' });
  state.screenshots.push({ module: moduleName(url), route: routeKey(url), file: filePath, note });
  return filePath;
}

async function visibleDescriptors(page) {
  return page.evaluate(() => {
    const nodes = Array.from(document.querySelectorAll('button,a,[role="button"],[role="tab"],[role="menuitem"],input,select,textarea'));
    let id = 0;
    return nodes.map((el) => {
      const rect = el.getBoundingClientRect();
      const style = getComputedStyle(el);
      const visible = rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
      if (!visible) return null;
      const codexId = `codex-${id++}`;
      el.setAttribute('data-codex-id', codexId);
      const text = (el.innerText || el.getAttribute('aria-label') || el.getAttribute('title') || el.getAttribute('placeholder') || '').replace(/\s+/g, ' ').trim();
      return {
        id: codexId,
        tag: el.tagName.toLowerCase(),
        role: el.getAttribute('role') || '',
        text: text.slice(0, 100),
        type: el.getAttribute('type') || '',
        href: el.getAttribute('href') || '',
      };
    }).filter(Boolean);
  });
}

async function collectRoutes(page) {
  const urls = await page.evaluate((base) => {
    const origin = new URL(base).origin;
    const result = new Set();
    for (const el of document.querySelectorAll('a[href], [role="link"][data-href]')) {
      const raw = el.getAttribute('href') || el.getAttribute('data-href');
      if (!raw || raw.startsWith('#') || raw.startsWith('javascript:') || raw.startsWith('mailto:')) continue;
      try {
        const url = new URL(raw, base);
        if (url.origin === origin && !/\/login|\/logout|\/oauth/i.test(url.pathname)) result.add(`${url.origin}${url.pathname}${url.search}`);
      } catch {}
    }
    return [...result];
  }, BASE);
  return urls;
}

function shouldInteract(item) {
  if (item.tag === 'a') return false;
  if (dangerous.test(item.text)) return false;
  if (item.role === 'menuitem' && !/顾客经营|经营工具|拓客工具/.test(item.text)) return true;
  if (item.tag === 'select' || item.tag === 'input' || item.tag === 'textarea') return true;
  return Boolean(item.text && (interactive.test(item.text) || item.role === 'tab' || item.role === 'menuitem'));
}

async function closeTransientState(page) {
  await page.keyboard.press('Escape').catch(() => {});
  await page.waitForTimeout(250);
}

async function interact(page, routeUrl) {
  const all = await visibleDescriptors(page);
  const candidates = all.filter(shouldInteract).slice(0, MAX_CONTROLS);
  const seen = new Set();
  for (const item of candidates) {
    if (seen.has(item.text)) continue;
    seen.add(item.text);
    const locator = page.locator(`[data-codex-id="${item.id}"]`).first();
    if (!(await locator.count())) continue;
    try {
      await locator.scrollIntoViewIfNeeded({ timeout: 3000 });
      if (!(await locator.isVisible())) continue;
      const before = page.url();
      if (item.tag === 'input' || item.tag === 'textarea') {
        await locator.focus({ timeout: 3000 });
      } else if (item.tag === 'select') {
        await locator.focus({ timeout: 3000 });
      } else {
        await locator.click({ timeout: 5000 });
      }
      await page.waitForTimeout(450);
      await page.waitForLoadState('networkidle', { timeout: 2500 }).catch(() => {});
      const label = item.text || item.role || item.tag;
      await capture(page, routeUrl, `交互_${label}`, `control:${item.tag}/${item.role || '-'};before:${before}`);
      await closeTransientState(page);
      if (page.url() !== routeUrl && page.url() !== before) {
        await page.goto(routeUrl, { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => {});
        await waitForApp(page);
      }
    } catch (error) {
      state.errors.push({ route: routeKey(page.url() || routeUrl), control: item.text, error: String(error.message || error) });
      await closeTransientState(page);
    }
  }
}

async function clickAndCapture(page, locator, label, routeUrl, note = '') {
  try {
    await locator.scrollIntoViewIfNeeded({ timeout: 3000 });
    if (!(await locator.isVisible())) return false;
    const before = page.url();
    await locator.click({ timeout: 6000 });
    await page.waitForTimeout(450);
    await page.waitForLoadState('networkidle', { timeout: 2500 }).catch(() => {});
    await capture(page, routeUrl || before, label, note || `before:${before}`);
    return true;
  } catch (error) {
    state.errors.push({ route: routeKey(page.url() || routeUrl || START), control: label, error: String(error.message || error) });
    return false;
  }
}

async function collectSidebar(page) {
  const base = page.url();
  const found = [];
  const menuLabels = await page.locator('li.ant-menu-item:not(.ant-menu-item-disabled)').evaluateAll((els) => els.map((el) => (el.innerText || '').replace(/\s+/g, ' ').trim()).filter(Boolean));
  const uniqueLabels = [...new Set(menuLabels)];
  for (const label of uniqueLabels) {
    const item = page.locator('li.ant-menu-item:not(.ant-menu-item-disabled)').filter({ hasText: label }).first();
    if (!(await item.count())) continue;
    const clicked = await clickAndCapture(page, item, `模块_${label}`, base, `sidebar:${label}`);
    if (clicked && page.url() !== base) {
      const route = page.url();
      found.push(route);
      const key = routeKey(route);
      if (!state.routes.some((r) => r.route === key)) state.routes.push({ requested: base, route: key, status: 200, finalUrl: route, title: await page.title().catch(() => ''), source: 'sidebar' });
      await collectRoutes(page).then((routes) => routes.forEach((r) => state.routes.find((x) => x.route === key) && (state.routes.find((x) => x.route === key).discovered = routes.map(routeKey))));
    }
    await page.goto(base, { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => {});
    await waitForApp(page);
  }
  const submenus = await page.locator('li.ant-menu-submenu:not(.ant-menu-submenu-disabled)').evaluateAll((els) => els.map((el) => {
    const title = el.querySelector('.ant-menu-submenu-title');
    return (title?.innerText || el.innerText || '').replace(/\s+/g, ' ').trim();
  }).filter(Boolean));
  for (const label of [...new Set(submenus)]) {
    await page.goto(base, { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => {});
    await waitForApp(page);
    const submenu = page.locator('li.ant-menu-submenu:not(.ant-menu-submenu-disabled)').filter({ hasText: label }).first();
    if (!(await submenu.count())) continue;
    await clickAndCapture(page, submenu.locator('.ant-menu-submenu-title').first(), `菜单_${label}`, base, `submenu:${label}`);
    const children = await submenu.locator('li.ant-menu-item:not(.ant-menu-item-disabled)').evaluateAll((els) => els.map((el) => (el.innerText || '').replace(/\s+/g, ' ').trim()).filter(Boolean));
    for (const childLabel of [...new Set(children)]) {
      const child = submenu.locator('li.ant-menu-item:not(.ant-menu-item-disabled)').filter({ hasText: childLabel }).last();
      await clickAndCapture(page, child, `子模块_${label}_${childLabel}`, base, `submenu:${label};child:${childLabel}`);
      if (page.url() !== base) {
        const route = page.url();
        found.push(route);
        const key = routeKey(route);
        if (!state.routes.some((r) => r.route === key)) state.routes.push({ requested: base, route: key, status: 200, finalUrl: route, title: await page.title().catch(() => ''), source: 'sidebar-child' });
      }
      await page.goto(base, { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => {});
      await waitForApp(page);
      const reopen = page.locator('li.ant-menu-submenu:not(.ant-menu-submenu-disabled)').filter({ hasText: label }).first();
      if (await reopen.count()) await reopen.locator('.ant-menu-submenu-title').first().click().catch(() => {});
    }
  }
  return [...new Set(found)];
}

async function collectAppointmentControls(page) {
  const base = page.url();
  const controls = [
    ['.ant-calendar-picker', '预约_日期选择器'],
    ['.mode-item:not(.active)', '预约_房间维度'],
    ['.ant-select-selection--single', '预约_门店下拉'],
    ['.state-item', '预约_状态筛选'],
    ['.text-color-gray', '预约_自定义颜色'],
    ['.add-btn', '预约_新增预约表单'],
  ];
  for (const [selector, label] of controls) {
    await page.goto(base, { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => {});
    await waitForApp(page);
    const loc = page.locator(selector).first();
    if (!(await loc.count())) continue;
    await clickAndCapture(page, loc, label, base, `appointment:${selector}`);
    await closeTransientState(page);
  }
  await page.goto(base, { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => {});
  await waitForApp(page);
  const topControls = ['获取手机APP', '考勤打卡码', '下载中心', '负责人'];
  for (const label of topControls) {
    const loc = page.locator('body *').filter({ hasText: new RegExp(`^${label}$`) }).filter({ visible: true }).first();
    if (!(await loc.count())) continue;
    await clickAndCapture(page, loc, `顶部_${label}`, base, `header:${label}`);
    await closeTransientState(page);
    await page.goto(base, { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => {});
    await waitForApp(page);
  }
}

async function main() {
  await ensureDir(OUTPUT);
  const browser = await chromium.launch({ headless: true, executablePath: CHROME, args: ['--disable-gpu', '--no-sandbox'] });
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    deviceScaleFactor: 1,
    extraHTTPHeaders: {
      Authorization: `Bearer ${TOKEN}`,
      'Cache-Control': 'no-cache',
    },
  });
  await context.addCookies([
    { name: 'Token', value: TOKEN, domain: 'saas.wikemi.com', path: '/', secure: true },
    { name: 'LinkState', value: '1', domain: 'saas.wikemi.com', path: '/', secure: true },
  ]);
  const page = await context.newPage();
  page.on('pageerror', (error) => state.errors.push({ route: routeKey(page.url() || START), pageerror: String(error.message || error) }));
  page.on('response', (response) => {
    if (response.status() >= 500) state.errors.push({ route: routeKey(response.url()), status: response.status(), url: response.url() });
  });

  const queue = [START];
  const visited = new Set();
  while (queue.length && visited.size < MAX_ROUTES) {
    const target = queue.shift();
    const key = routeKey(target);
    if (visited.has(key)) continue;
    visited.add(key);
    const record = { requested: target, route: key, status: null, finalUrl: null, title: null, bodySample: null, screenshotsBefore: state.screenshots.length };
    try {
      const response = await page.goto(target, { waitUntil: 'domcontentloaded', timeout: 45000 });
      record.status = response?.status() ?? null;
      await waitForApp(page);
      record.finalUrl = page.url();
      record.title = await page.title().catch(() => '');
      record.bodySample = (await page.locator('body').innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 500);
      await capture(page, target, '页面', `status:${record.status};title:${record.title}`);
      const discovered = await collectRoutes(page);
      for (const route of discovered) if (!visited.has(routeKey(route)) && queue.length + visited.size < MAX_ROUTES) queue.push(route);
      if (key === '/operate/appointment') {
        const sidebarRoutes = await collectSidebar(page);
        for (const route of sidebarRoutes) if (!visited.has(routeKey(route)) && queue.length + visited.size < MAX_ROUTES) queue.push(route);
        await collectAppointmentControls(page);
      }
      await interact(page, target);
      record.screenshotsAfter = state.screenshots.length;
      record.discovered = discovered.map(routeKey);
    } catch (error) {
      record.error = String(error.message || error);
      state.errors.push({ route: key, error: record.error });
    }
    state.routes.push(record);
    await fs.writeFile(path.join(OUTPUT, 'manifest.json'), JSON.stringify(state, null, 2), 'utf8');
  }

  state.finishedAt = new Date().toISOString();
  state.authenticatedHeuristic = !/\/login/i.test(page.url()) && !/登录|请先登录|重新登录/i.test((await page.locator('body').innerText().catch(() => '')).slice(0, 1000));
  await fs.writeFile(path.join(OUTPUT, 'manifest.json'), JSON.stringify(state, null, 2), 'utf8');
  await browser.close();
  console.log(JSON.stringify({ output: OUTPUT, visited: visited.size, screenshots: state.screenshots.length, errors: state.errors.length, finalUrl: state.routes.at(-1)?.finalUrl, authenticatedHeuristic: state.authenticatedHeuristic }, null, 2));
}

main().catch(async (error) => {
  state.fatal = String(error.stack || error);
  await ensureDir(OUTPUT).catch(() => {});
  await fs.writeFile(path.join(OUTPUT, 'manifest.json'), JSON.stringify(state, null, 2), 'utf8').catch(() => {});
  console.error(error.stack || error);
  process.exitCode = 1;
});
