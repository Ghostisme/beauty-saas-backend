import fs from 'node:fs/promises';
import path from 'node:path';

const output = path.resolve(process.argv[2] || 'C:/Users/Administrator/Desktop/峰的saas/2026-09-26_全量截图');
const manifestFile = path.join(output, 'manifest.json');

const directRoutes = {
  home: ['/home', '首页'],
  marketing_tool_index: ['/marketing/tool/index', '营销工具'],
  message_info_setting_index: ['/message/info/setting/index', '短信'],
  operate_appointment: ['/operate/appointment', '预约'],
  operate_billing_index: ['/operate/billing/index', '开单'],
  operate_bookkeeping_expenditure_content: ['/operate/bookkeeping/expenditure/content?startTime=2026-09-01&endTime=2026-09-26&type=2&categoryIds=', '收支'],
  operate_inventory_account: ['/operate/inventory/account', '成本核算'],
  operate_inventory_batch: ['/operate/inventory/batch', '批次管理'],
  operate_inventory_call: ['/operate/inventory/call', '调拨管理'],
  operate_inventory_detail: ['/operate/inventory/detail', '出入明细'],
  operate_inventory_liquidation: ['/operate/inventory/liquidation', '库存盘点'],
  operate_inventory_management: ['/operate/inventory/management', '库存管理'],
  operate_member_storeMember_content: ['/operate/member/storeMember/content', '顾客'],
  operate_orderForGoods_orderList_content: ['/operate/orderForGoods/orderList/content', '订单'],
  operate_systemRecord_orderOperation_index: ['/operate/systemRecord/orderOperation/index?startTime=2026-09-01&endTime=2026-09-26&type=setting', '系统日志'],
  operate_target_list_index: ['/operate/target/list/index', '目标'],
  operate_yuji_business_index: ['/operate/yuji/business/index', '数据报表'],
};

// These modules are reached when a content-area tab changes the route.
const supplementalRoutes = {
  message_info_const_index: ['/message/info/const/index', '短信发送记录'],
  message_info_recharge_index: ['/message/info/recharge/index', '短信余额充值'],
  operate_bookkeeping_otherRecord_content: ['/operate/bookkeeping/otherRecord/content', '其他收入记录'],
  operate_member_deposit_content: ['/operate/member/deposit/content', '顾客寄存'],
  operate_member_followUp_content: ['/operate/member/followUp/content', '顾客跟进'],
  operate_member_screen_content: ['/operate/member/screen/content', '高级查询'],
  operate_member_visit_situation: ['/operate/member/visit/situation', '顾客回访'],
  operate_orderForGoods_balancePayment_content: ['/operate/orderForGoods/balancePayment/content', '尾款单'],
  operate_orderForGoods_registration_content: ['/operate/orderForGoods/registration/content', '未完成订单列表'],
  operate_systemRecord_cardOperation_index: ['/operate/systemRecord/cardOperation/index', '顾客会员卡操作'],
  operate_systemRecord_googleOperation_index: ['/operate/systemRecord/googleOperation/index', '顾客操作'],
  operate_systemRecord_itemOperation_index: ['/operate/systemRecord/itemOperation/index', '品项操作'],
  operate_systemRecord_otherOperation_index: ['/operate/systemRecord/otherOperation/index', '其他操作日志'],
  operate_systemRecord_staffOperation_index: ['/operate/systemRecord/staffOperation/index', '员工操作'],
  operate_target_apart_index: ['/operate/target/apart/index', '员工行动计划'],
  operate_yuji_beautician_collect: ['/operate/yuji/beautician/collect', '美容师统计表'],
  operate_yuji_customer_check: ['/operate/yuji/customer/check', '客户盘点表'],
  operate_yuji_inDebt_customerAll: ['/operate/yuji/inDebt/customerAll', '负债报表'],
  operate_yuji_itemCard_index: ['/operate/yuji/itemCard/index', '品项卡报表'],
  operate_yuji_newCustomer_index: ['/operate/yuji/newCustomer/index', '新客数据表'],
  operate_yuji_reportCenter_index: ['/operate/yuji/reportCenter/index', '报表中心'],
};

const routeMap = { ...directRoutes, ...supplementalRoutes };
const safeText = (value) => String(value || '').replace(/\s+/g, ' ').trim();
const routeKey = (value) => {
  const url = new URL(value, 'https://saas.wikemi.com');
  return `${url.pathname}${url.search}`;
};
const classify = (file) => {
  const name = path.basename(file);
  if (name.includes('_页签_')) return '内容页签切换';
  if (name.includes('_筛选_')) return '筛选或维度切换';
  if (name.includes('_交互_')) return '控件交互';
  if (name.includes('_菜单_')) return '菜单切换';
  if (name.includes('_顶部_')) return '顶部控件';
  return '页面初始状态';
};
const labelFromFile = (file) => {
  const name = path.basename(file, path.extname(file));
  return safeText(name.replace(/^\d+_/, '').replace(/^(页面|页签|筛选|交互|菜单|顶部)_?/, '').replace(/_/g, ' '));
};

async function walk(dir) {
  const entries = await fs.readdir(dir, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) files.push(...await walk(full));
    else if (entry.isFile() && entry.name.toLowerCase().endsWith('.png')) files.push(full);
  }
  return files;
}

const previous = JSON.parse(await fs.readFile(manifestFile, 'utf8').catch(() => '{}'));
const pngFiles = (await walk(output)).sort((a, b) => a.localeCompare(b, 'zh-CN'));
const screenshots = pngFiles.map((file) => {
  const relative = path.relative(output, file);
  const module = relative.split(path.sep)[0];
  const meta = routeMap[module] || [`/${module.replace(/_/g, '/')}`, module];
  return {
    module,
    route: routeKey(meta[0]),
    file,
    label: labelFromFile(file),
    note: classify(file),
  };
});

const counts = new Map();
for (const item of screenshots) counts.set(item.module, (counts.get(item.module) || 0) + 1);

const routes = [...(previous.routes || [])];
for (const [module, [route, label]] of Object.entries(routeMap)) {
  const existing = routes.find((item) => item.route === routeKey(route));
  if (existing) {
    existing.screenshotCount = counts.get(module) || 0;
    continue;
  }
  if (!counts.has(module)) continue;
  routes.push({
    route: routeKey(route),
    label,
    status: null,
    finalUrl: `https://saas.wikemi.com${routeKey(route)}`,
    title: '秒绘管家',
    screenshotCount: counts.get(module),
    verifiedByScreenshot: true,
  });
}

const moduleInventory = [...counts.entries()].sort(([a], [b]) => a.localeCompare(b, 'zh-CN')).map(([module, screenshotCount]) => {
  const [route, label] = routeMap[module] || [`/${module.replace(/_/g, '/')}`, module];
  return { module, label, route: routeKey(route), screenshotCount };
});

const result = {
  ...previous,
  output,
  finishedAt: new Date().toISOString(),
  routes,
  moduleInventory,
  screenshots,
  screenshotCount: screenshots.length,
  sourceDirectories: [
    '2026-09-26_已确认模块-v3',
    '2026-09-26_自动采集-v2/operate_appointment',
    '2026-09-26_全量截图 supplementary tab/filter capture',
  ],
};

await fs.writeFile(manifestFile, JSON.stringify(result, null, 2), 'utf8');
console.log(JSON.stringify({ output, modules: moduleInventory.length, routes: routes.length, screenshots: screenshots.length, errors: (result.errors || []).length }, null, 2));
