const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {createRequire}=require('node:module');process.chdir(path.resolve(__dirname,'..'));
const {chromium}=createRequire(path.resolve('frontend/package.json'))('playwright');
const env=process.env.TICKET_RUNTIME_ENV||'work/runtime.env';
const cfg=Object.fromEntries(fs.readFileSync(env,'utf8').trim().split('\n').filter(l=>l&&!l.startsWith('#')).map(l=>{const i=l.indexOf('=');return [l.slice(0,i),l.slice(i+1)]}));
const base=process.env.TICKET_FRONTEND_URL||'http://127.0.0.1:15180';assert.equal(new URL(base).hostname,'127.0.0.1');
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.TICKET_CHROMIUM||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:1440,height:1000}}),checks=[];
 const check=name=>{checks.push(name);console.log('PASS '+name)};
 const handle='browser-'+Date.now(),password=require('node:crypto').randomUUID(),display='<img src=x onerror="window.xss=true">';
 try{
  await page.goto(base+'/account');await page.waitForURL('**/account/login');check('anonymous member page redirects to customer login');
  await page.getByRole('button',{name:'還沒有帳號？建立帳號'}).click();await page.getByLabel('顧客帳號',{exact:true}).fill(handle);await page.getByLabel('顯示名稱',{exact:true}).fill(display);await page.getByLabel('密碼',{exact:true}).fill(password);
  const lost=Promise.withResolvers();let registrationBody;
  await page.route('**/api/customer/auth/register',async route=>{registrationBody=route.request().postData();try{const response=await route.fetch();assert.equal(response.status(),200);await route.abort('failed');lost.resolve()}catch(e){lost.reject(e)}});
  await page.getByRole('button',{name:'建立顧客帳號 →',exact:true}).click();await lost.promise;await page.getByRole('alert').filter({hasText:'相同帳號登入確認'}).waitFor();assert.equal(await page.getByLabel('密碼',{exact:true}).inputValue(),'');check('committed registration response loss directs login and clears password');
  const storage=await page.evaluate(()=>({local:{...localStorage},session:{...sessionStorage}}));assert(!JSON.stringify(storage).includes(password));assert(!JSON.stringify(storage).includes(registrationBody));check('password and registration body never persist in browser storage');
  await page.getByRole('button',{name:'已有帳號，改用登入'}).click();await page.getByLabel('密碼',{exact:true}).fill(password);await page.getByRole('button',{name:'登入會員 →',exact:true}).click();await page.waitForURL('**/account');await page.getByRole('heading',{name:display,exact:true}).waitFor();check('same handle login resolves unknown registration result');
  assert.equal(await page.evaluate(()=>window.xss),undefined);assert.equal(await page.locator('img').count(),0);check('customer display name escaped as text');
  const cookies=await page.context().cookies(base+'/api/customer/auth/me');const customer=cookies.find(c=>c.name==='TICKET_CUSTOMER_SESSION');assert(customer.httpOnly);assert.equal(customer.sameSite,'Strict');assert.equal(customer.path,'/api/customer');
  assert(!(await page.evaluate(()=>document.cookie)).includes('TICKET_CUSTOMER_SESSION'));check('session cookie cannot be read by page script');
  await page.reload();await page.getByRole('heading',{name:display,exact:true}).waitFor();check('customer login survives page reload');
  await page.goto(base+'/staff');await page.waitForURL('**/staff/login');check('customer cookie does not grant staff access');
  await page.getByLabel('員工帳號').fill(cfg.TICKET_STAFF_USERNAME);await page.getByLabel('密碼',{exact:true}).fill(cfg.TICKET_STAFF_PASSWORD);await page.getByRole('button',{name:'登入後台'}).click();await page.waitForURL('**/staff');await page.getByRole('heading',{name:'活動管理',exact:true}).waitFor();
  await page.goto(base+'/account');await page.getByRole('heading',{name:display,exact:true}).waitFor();check('staff login preserves customer identity');
  const logoutLost=Promise.withResolvers();let intercepted=false;
  await page.route('**/api/customer/auth/logout',async route=>{if(!intercepted){intercepted=true;try{const response=await route.fetch();assert.equal(response.status(),200);await route.abort('failed');logoutLost.resolve()}catch(e){logoutLost.reject(e)}}else await route.continue()});
  await page.getByRole('button',{name:'登出會員',exact:true}).click();await logoutLost.promise;await page.getByRole('alert').filter({hasText:'重試登出'}).waitFor();await page.getByRole('button',{name:'登出會員',exact:true}).click();await page.waitForURL('**/account/login');check('committed logout response loss safely retries while anonymous');
  await page.goto(base+'/staff');await page.getByRole('heading',{name:'活動管理',exact:true}).waitFor();check('customer logout preserves staff authorization');
  await page.goto(base+'/account/login');await page.getByLabel('顧客帳號',{exact:true}).fill(handle);await page.getByLabel('密碼',{exact:true}).fill(password);await page.getByRole('button',{name:'登入會員 →',exact:true}).click();await page.waitForURL('**/account');
  await page.goto(base+'/staff');await page.getByRole('heading',{name:'活動管理',exact:true}).waitFor();await page.getByRole('button',{name:'登出',exact:true}).click();await page.waitForURL('**/staff/login');await page.goto(base+'/account');await page.getByRole('heading',{name:display,exact:true}).waitFor();check('staff logout preserves customer authorization');
  await page.setViewportSize({width:393,height:852});assert(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth));await page.screenshot({path:'work/customer-mobile.png',fullPage:true});check('member page and navigation fit mobile width');
  await page.getByRole('button',{name:'登出會員',exact:true}).click();await page.waitForURL('**/account/login');await page.getByRole('button',{name:'還沒有帳號？建立帳號'}).click();assert(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth));await page.screenshot({path:'work/customer-login-mobile.png',fullPage:true});check('customer registration form fits mobile width');
  fs.writeFileSync('work/browser-customer.json',JSON.stringify({transport:'Chromium against JAR + production dist',passed:checks.length,checks},null,2)+'\n');console.log('Customer browser checks passed: '+checks.length);
 }catch(e){await page.screenshot({path:'work/customer-browser-failure.png',fullPage:true});throw e}finally{await browser.close()}
})().catch(e=>{console.error(e.message);process.exit(1)});
