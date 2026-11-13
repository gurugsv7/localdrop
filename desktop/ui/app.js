'use strict';
const invoke=(cmd,args={})=>window.__TAURI_INTERNALS__.invoke(cmd,args);
const el=id=>document.getElementById(id);
let previousPaired=null,historyKey='',latestId=null,toastTimer,refreshTimer,refreshing=false;
function toast(message,error=false){clearTimeout(toastTimer);const box=el('toast');box.textContent=message;box.classList.toggle('error',error);box.hidden=false;toastTimer=setTimeout(()=>box.hidden=true,4500);}
function page(name){for(const tab of ['overview','preferences']){el(tab).hidden=tab!==name;el('nav-'+tab).classList.toggle('active',tab===name);if(tab===name)el('nav-'+tab).setAttribute('aria-current','page');else el('nav-'+tab).removeAttribute('aria-current');}hideQr();}
function hideQr(){el('qr').hidden=true;el('qr').replaceChildren();}
function size(bytes){return bytes<1048576?`${Math.max(1,Math.round(bytes/1024))} KB`:`${(bytes/1048576).toFixed(1)} MB`;}
function icon(type){const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');const use=document.createElementNS('http://www.w3.org/2000/svg','use');use.setAttribute('href','#i-'+type);svg.append(use);svg.setAttribute('aria-hidden','true');return svg;}
function renderHistory(rows){const key=JSON.stringify(rows);if(key===historyKey)return;historyKey=key;el('history').replaceChildren();for(const [index,m] of rows.entries()){
 const images=m.items.filter(i=>i.mime.startsWith('image/')).length;
 const type=m.items.length?(images===m.items.length?(images===1?'Image':`${images} images`):`${m.items.length} file${m.items.length===1?'':'s'}`):'Text';
 const row=document.createElement('div');row.className='row';const glyph=document.createElement('span');glyph.className='type-icon';glyph.append(icon(m.items.length?(images?'image':'file'):'text'));
 const info=document.createElement('div');info.className='row-info';const label=document.createElement('span');label.className='row-label';label.textContent=m.text||m.items.map(i=>i.name).join(', ');label.title=label.textContent;
 const meta=document.createElement('p');meta.className='row-meta';meta.textContent=`${type} · from Android${m.items.length?' · '+size(m.items.reduce((n,i)=>n+i.size,0)):''}${index===0?' · latest':''}`;info.append(label,meta);
 const b=document.createElement('button');b.className='text-button';b.textContent='Copy ↗';b.setAttribute('aria-label',`Copy ${type.toLowerCase()} transfer`);b.onclick=()=>action('restore',{id:m.id},'Copied. Ready to paste.',b,true);row.append(glyph,info,b);el('history').append(row);
 }if(!rows.length){const empty=document.createElement('div');empty.className='empty';const title=document.createElement('strong');title.textContent='Room for your next thought.';const detail=document.createElement('p');detail.textContent='Share to LocalDrop on Android. Your latest three transfers appear here.';empty.append(title,detail);el('history').append(empty);}
 el('sequence').hidden=!rows.some(m=>m.items.filter(i=>i.mime.startsWith('image/')).length>1);
 if(latestId!==null&&rows[0]?.id&&rows[0].id!==latestId)el('paste-title').textContent='Your next paste is ready.';
 latestId=rows[0]?.id??'';
}
async function refresh(){if(refreshing)return;refreshing=true;try{const s=await invoke('status');el('status').textContent=s.status;el('status').hidden=s.status==='Ready on local Wi-Fi';el('pair-badge').textContent=s.paired?'PAIRED':'NOT PAIRED';el('pair-badge').classList.toggle('waiting',!s.paired);el('device-name').textContent=s.device_name;el('pair-description').textContent=s.paired?'Trusted device · encrypted over Wi-Fi':'Pair once. Share over local Wi-Fi.';el('pair').textContent=s.paired?'Manage pairing ↗':'Pair a phone ↗';if(s.paired&&previousPaired===false){hideQr();toast('Phone paired. You’re ready to share.');}previousPaired=s.paired;el('usage').textContent=`${s.used_mib} MiB used of ${s.cache_mib} MiB`;if(document.activeElement!==el('limit'))el('limit').value=s.cache_mib;el('notifications').checked=s.notifications;renderHistory(s.history);}catch(e){el('status').textContent=String(e);}finally{refreshing=false;}}
async function action(cmd,args={},message,button,copied=false){if(button)button.disabled=true;try{await invoke(cmd,args);await refresh();if(copied)el('paste-title').textContent='Your next paste is ready.';if(message)toast(message);}catch(e){toast(String(e),true);}finally{if(button)button.disabled=false;}}
el('nav-overview').onclick=()=>page('overview');el('nav-preferences').onclick=()=>page('preferences');document.querySelector('.brand').onclick=e=>{e.preventDefault();page('overview');};
el('pair').onclick=async()=>{const box=el('qr');if(!box.hidden){hideQr();return;}try{const svg=await invoke('pairing_qr');const img=document.createElement('img');img.alt='Private pairing QR for LocalDrop Android';img.src='data:image/svg+xml;charset=utf-8,'+encodeURIComponent(svg);const note=document.createElement('p');note.textContent='On your phone: Preferences → Pairing → Scan laptop QR. Keep this code private.';box.replaceChildren(img,note);box.hidden=false;}catch(e){toast(String(e),true);}};
el('next').onclick=()=>action('next_image',{},'Next image copied. Press Ctrl+V.',el('next'),true);
el('send').onclick=()=>action('send_clipboard',{},'Queued for your phone. Tap Receive in Android.',el('send'));
el('save').onclick=()=>action('set_cache',{mib:Number(el('limit').value)},'Cache limit saved.',el('save'));
el('notifications').onchange=()=>action('set_notifications',{enabled:el('notifications').checked},'Notification preference saved.');
el('test-notification').onclick=()=>action('test_notification',{},'Desktop preview sent.',el('test-notification'));
function schedule(){clearTimeout(refreshTimer);if(!document.hidden){refresh().finally(()=>{refreshTimer=setTimeout(schedule,3000);});}}
document.addEventListener('visibilitychange',()=>{if(document.hidden)clearTimeout(refreshTimer);else schedule();});
schedule();
