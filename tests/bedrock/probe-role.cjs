const b=require('bedrock-protocol'),fs=require('fs'),{randomUUID}=require('crypto');
const c=b.createClient({host:'127.0.0.1',port:19188,version:'1.26.51',offline:true,username:'NCTestA',transport:'raknet',conLog:()=>{}});
const packets=[];c.on('error',console.error);c.on('update_abilities',p=>{packets.push(p);console.log(JSON.stringify(p,(_,v)=>typeof v==='bigint'?String(v):v))});
c.on('text',p=>console.log(p.message));
function command(command){c.queue('command_request',{command,origin:{type:'player',uuid:randomUUID(),request_id:'',player_entity_id:0n},internal:false,version:''})}
c.on('spawn',()=>{setTimeout(()=>command('/ncfdiag member'),1500);setTimeout(()=>command('/ncfdiag operator'),3500)});
setTimeout(()=>{fs.writeFileSync('results/role-diagnostic.json',JSON.stringify(packets,(_,v)=>typeof v==='bigint'?String(v):v,2));c.close();process.exit()},11000);
