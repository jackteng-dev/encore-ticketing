// Real Chromium against Nginx + verified dist. Only own loopback/private fixtures.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {createRequire}=require('node:module');
const {chromium}=createRequire(path.resolve(__dirname,'../frontend/package.json'))('playwright');
const phase=process.argv[2];assert(['seed','resume'].includes(phase));
const cfg=Object.fromEntries(fs.readFileSync(process.env.TICKET_RUNTIME_ENV,'utf8').trim().split('\n').map(l=>{const i=l.indexOf('=');return[l.slice(0,i),l.slice(i+1)]}));
const base='http://127.0.0.1:'+cfg.TICKET_GATEWAY_PORT;
const fixture=path.resolve(process.env.TICKET_DEPLOY_FIXTURE),output=path.resolve(process.env.TICKET_DEPLOY_BROWSER_OUTPUT);
const work=path.resolve(__dirname,'../work');assert(fixture.startsWith(work+'/')&&output.startsWith(work+'/'));
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.TICKET_CHROMIUM||(fs.existsSync('/usr/bin/chromium')?'/usr/bin/chromium':undefined),headless:true,args:['--no-sandbox']});let context;const checks=[];
 try{
  const old=phase==='resume'?JSON.parse(fs.readFileSync(fixture,'utf8')):null;
  context=await browser.newContext({viewport:{width:393,height:852},...(old?{storageState:old.storage}:{})});
  if(old)await context.addInitScript(({storage,origin})=>{if(location.origin===origin)for(const[k,v]of Object.entries(storage))sessionStorage.setItem(k,v);},{storage:old.sessionStorage,origin:base});
  const page=await context.newPage();
  if(phase==='seed'){
   await page.goto(base+'/staff');await page.waitForURL('**/staff/login');
   await page.getByLabel('員工帳號').fill(cfg.TICKET_STAFF_USERNAME);await page.getByLabel('密碼',{exact:true}).fill(cfg.TICKET_STAFF_PASSWORD);await page.getByRole('button',{name:'登入後台'}).click();await page.waitForURL('**/staff');checks.push('staff login through container gateway');
   await page.getByRole('button',{name:'＋ 新增'}).click();const title='部署驗收 '+Date.now();
   await page.getByLabel('活動名稱',{exact:true}).fill(title);await page.getByLabel('活動代碼').fill('deploy-browser-'+Date.now());await page.getByLabel('場地',{exact:true}).fill('隔離合成場地');await page.getByLabel('活動介紹').fill('免費展示合成 fixture');
   let originalBody;const done=Promise.withResolvers();
   await page.route('**/api/admin/events',async route=>{
    if(route.request().method()==='POST'&&!originalBody){originalBody=route.request().postData();try{const r=await route.fetch();assert.equal(r.status(),200);await route.abort('failed');done.resolve();}catch(e){done.reject(e);}}else await route.continue();
   });
   await page.getByRole('button',{name:'建立草稿'}).click();await done.promise;await page.getByText('有一筆操作尚未確認。',{exact:true}).waitFor();checks.push('actual committed catalog command with client response deliberately discarded');
   const saved={storage:await context.storageState(),sessionStorage:await page.evaluate(()=>({...sessionStorage})),originalBody,title};fs.writeFileSync(fixture,JSON.stringify(saved),{mode:0o600});
  }else{
   await page.goto(base+'/staff');await page.getByRole('button',{name:'重試原操作'}).waitFor();assert(await page.getByRole('button',{name:'＋ 新增'}).isDisabled());checks.push('same staff cookie and pending survive API node outage');
   let body;page.on('request',r=>{if(r.method()==='POST'&&r.url().endsWith('/api/admin/events'))body=r.postData();});
   await page.getByRole('button',{name:'重試原操作'}).click();await page.getByRole('heading',{name:old.title,exact:true}).waitFor();assert.equal(body,old.originalBody);assert.equal(await page.getByText('有一筆操作尚未確認。',{exact:true}).count(),0);checks.push('same UUID and exact body replay via remaining API');
   assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));checks.push('mobile production UI no horizontal overflow');
   await page.screenshot({path:output.replace(/\.json$/,'.png'),fullPage:true});
  }
  fs.writeFileSync(output,JSON.stringify({phase,passed:checks.length,checks,physicalDevice:false},null,2)+'\n',{mode:0o600});console.log(phase+': '+checks.length+' browser checks passed');
 }finally{if(context)await context.close();await browser.close();}
})().catch(e=>{console.error(e.message);process.exit(1)});
