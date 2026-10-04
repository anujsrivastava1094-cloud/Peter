#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";
const root=process.cwd(), failures=[];
const pass=m=>console.log("PASS:",m), fail=m=>failures.push(m);
const exists=p=>fs.existsSync(path.join(root,p));
function checkFile(p){exists(p)?pass("file exists: "+p):fail("missing required file: "+p);}
function checkDuplicateIds(html){
  const ids=[...html.matchAll(/\bid=["']([^"']+)["']/gi)].map(m=>m[1]);
  const seen=new Set(), dup=new Set();
  ids.forEach(id=>seen.has(id)?dup.add(id):seen.add(id));
  dup.size?fail("duplicate HTML ids: "+[...dup].join(", ")):pass("no duplicate HTML ids");
}
function checkScripts(html){
  const scripts=[...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi)].map(m=>m[1]);
  if(!scripts.length){fail("no inline scripts");return;}
  scripts.forEach((src,i)=>{try{new Function(src);pass("script "+(i+1)+" syntax");}catch(e){fail("script "+(i+1)+" syntax: "+e.message);}});
}
function checkAssets(html){
  const refs=[...html.matchAll(/(?:src|href)=["']([^"'#?]+)["']/gi)].map(m=>m[1]).filter(x=>x.startsWith("./")||x.startsWith("../"));
  [...new Set(refs)].forEach(ref=>exists(path.normalize(ref))?pass("asset exists: "+ref):fail("missing asset: "+ref));
}
function checkDuplicateFunctions(html){
  const names=[...html.matchAll(/function\s+([A-Za-z_$][\w$]*)\s*\(/g)].map(m=>m[1]);
  const counts=new Map();
  names.forEach(n=>counts.set(n,(counts.get(n)||0)+1));
  const dup=[...counts.entries()].filter(([,n])=>n>1).map(([name])=>name);
  dup.length?fail("duplicate function declarations: "+dup.join(", ")):pass("no duplicate function declarations");
}
function checkDangerousInlineErrors(html){
  if(/TODO\s*:\s*(?:FIX|BUG)/i.test(html)) fail("unresolved BUG/TODO marker");
  else pass("no unresolved BUG/TODO marker");
}

function checkFunctions(html){
  ["peterProcessCommand","peterAutomationCommand","peterActionRouter","peterGenericNavigation","peterAskCloudflareAI","peterSpeak","sendTextCommand","openDetail","renderAll"].forEach(n=>{
    new RegExp("function\\s+"+n+"\\s*\\(").test(html)?pass("function "+n):fail("missing function "+n);
  });
}
function checkWorker(){
  if(!exists("worker.js")){fail("missing worker.js");return;}
  try {
    execFileSync(process.execPath, ["--check", path.join(root, "worker.js")], { stdio: "pipe" });
    pass("worker.js syntax");
  } catch(e) {
    const detail = e.stderr?.toString() || e.stdout?.toString() || e.message;
    fail("worker.js syntax: " + detail.trim());
  }
}
function checkWorkflows(){
  const d=path.join(root,".github/workflows");
  if(!fs.existsSync(d)){fail("missing workflows");return;}
  const n=fs.readdirSync(d).filter(x=>/\.ya?ml$/.test(x)).length;
  n?pass(n+" workflow files"):fail("no workflow files");
}
try{
  const html=fs.readFileSync(path.join(root,"index.html"),"utf8");
  checkFile("index.html");checkFile("worker.js");checkFile("wrangler.jsonc");
  checkDuplicateIds(html);checkScripts(html);checkAssets(html);checkDuplicateFunctions(html);checkDangerousInlineErrors(html);checkFunctions(html);checkWorker();checkWorkflows();
}catch(e){fail("guardian crashed: "+e.stack);}
if(failures.length){console.error("\nPETER GUARDIAN FAILED");failures.forEach(x=>console.error("FAIL:",x));process.exit(1);}
console.log("\nPETER GUARDIAN GREEN");
