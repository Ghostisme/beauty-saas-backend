import fs from 'node:fs/promises';
const file = process.argv[2];
if (!file) throw new Error('manifest path required');
const data = JSON.parse(await fs.readFile(file, 'utf8'));
const redact = (value) => String(value || '')
  .replace(/\b1\d{10}\b/g, '[PHONE_REDACTED]')
  .replace(/\b\d{11}\b/g, '[PHONE_REDACTED]')
  .replace(/\bToken=[^;\s]+/gi, 'Token=[REDACTED]')
  .replace(/Bearer\s+[^\s]+/gi, 'Bearer [REDACTED]');
for (const route of data.routes || []) {
  route.bodySample = redact(route.bodySample);
  route.finalUrl = redact(route.finalUrl);
}
for (const error of data.errors || []) {
  error.error = redact(error.error);
  error.url = redact(error.url);
}
await fs.writeFile(file, JSON.stringify(data, null, 2), 'utf8');
