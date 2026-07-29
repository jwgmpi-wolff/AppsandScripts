import fs from 'node:fs/promises';
import path from 'node:path';
import { chromium } from 'playwright';

const viewport = { width: 1600, height: 1000 };
const outputDir = path.resolve(process.cwd(), 'docs', 'screenshots');

const routeScreens = [
  { route: '/#/', file: 'dashboard.png' },
  { route: '/#/usage', file: 'usage-explorer.png' },
  { route: '/#/projects', file: 'projects.png' },
  { route: '/#/models', file: 'models.png' },
  { route: '/#/alerts', file: 'alerts.png' },
  { route: '/#/recommendations', file: 'recommendations.png' },
];

const candidateBaseUrls = [
  process.env.BASE_URL,
  'http://localhost:5173',
  'http://localhost:5174',
].filter(Boolean);

async function canReach(url) {
  try {
    const res = await fetch(url, { redirect: 'follow' });
    return res.ok;
  } catch {
    return false;
  }
}

async function resolveBaseUrl() {
  for (const url of candidateBaseUrls) {
    if (await canReach(url)) {
      return url;
    }
  }

  throw new Error(
    'Could not reach local app. Start dev server with "npm run dev" or set BASE_URL.',
  );
}

async function main() {
  await fs.mkdir(outputDir, { recursive: true });

  const baseUrl = await resolveBaseUrl();
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport });
  const page = await context.newPage();

  for (const item of routeScreens) {
    const target = `${baseUrl}${item.route}`;
    await page.goto(target, { waitUntil: 'networkidle' });
    await page.waitForTimeout(500);

    const outPath = path.join(outputDir, item.file);
    await page.screenshot({ path: outPath, fullPage: true });
    console.log(`Saved ${path.relative(process.cwd(), outPath)} from ${target}`);
  }

  await browser.close();
  console.log('Screenshot capture complete.');
}

main().catch((err) => {
  console.error(err.message);
  process.exit(1);
});
