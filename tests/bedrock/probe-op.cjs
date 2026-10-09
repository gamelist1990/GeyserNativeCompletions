const b=require('bedrock-protocol'),fs=require('fs');
const c=b.createClient({host:'127.0.0.1',port:19188,version:'1.26.51',offline:true,username:'NCTestA',transport:'raknet'});
let count=0;c.on('error',console.error);c.on('disconnect',console.log);
c.on('available_commands',p=>{fs.mkdirSync('results',{recursive:true});fs.writeFileSync('results/op-'+(++count)+'.json',JSON.stringify(p,null,2));console.log('packet',count); for(const d of p.command_data){const e=p.enums[d.alias];const names=e?e.values.map(i=>p.enum_values[i]):[d.name];if(names.some(n=>['gamemode','give','minecraft:gamemode','minecraft:give'].includes(n))) console.log(JSON.stringify({names,command:d}));}});
setTimeout(()=>{c.close();process.exit()},16000);

