#!/usr/bin/env node
const fs=require("fs"),vm=require("vm");
const errors=[],warnings=[];
const read=p=>{if(!fs.existsSync(p)){errors.push("Missing required file: "+p);return "";}return fs.readFileSync(p,"utf8");};
const html=read("index.html"),worker=read("worker.js");
if(html){
 const scripts=[...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi)].map(m=>m[1]);
 scripts.forEach((code,i)=>{try{new vm.Script(code,{filename:"index.html#script-"+(i+1)});}catch(e){errors.push("index.html script "+(i+1)+" syntax error: "+e.message);}});
 const names=[...html.matchAll(/(^|[\n;{}])\s*function\s+([A-Za-z_$][\w$]*)\s*\(/g)].map(m=>m[2]),counts={};
 names.forEach(n=>counts[n]=(counts[n]||0)+1);
 Object.entries(counts).filter(([,n])=>n>1).forEach(([n,nc])=>errors.push("Duplicate function declaration: "+n+" ("+nc+" times)"));
 ["peterProcessCommand","peterSpeak","peterNormalizeCommand","peterActionRouter","peterGenericNavigation","peterAutomationCommand"].forEach(n=>{if(!new RegExp("function\\s+"+n+"\\s*\\(").test(html))errors.push("Required PETER function missing: "+n);});
}
if(worker){try{new vm.SourceTextModule(worker,{identifier:"worker.js"});}catch(e){try{new Function(worker.replace(/export\s+default\s+/,""));}catch(e2){errors.push("worker.js syntax error: "+e.message);}}}
if(!fs.existsSync("wrangler.jsonc"))errors.push("Missing wrangler.jsonc.");
console.log("PETER GUARDIAN");console.log("Errors:",errors.length,"Warnings:",warnings.length);errors.forEach(e=>console.error("ERROR:",e));warnings.forEach(w=>console.warn("WARN:",w));if(errors.length)process.exit(1);console.log("GUARDIAN: PASS");