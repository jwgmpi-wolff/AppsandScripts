async function scan() {
  const seeds = document.getElementById("seeds").value.split(/\r?\n/).map(s=>s.trim()).filter(Boolean)
  const pattern = document.getElementById("pattern").value || null
  if (!seeds.length) { alert('Add at least one seed URL'); return }
  const resp = await fetch('/scan', {method:'POST', headers:{'content-type':'application/json'}, body: JSON.stringify({seeds, pattern})})
  const data = await resp.json()
  renderResults(data.results)
}

function renderResults(results) {
  const el = document.getElementById('results')
  el.innerHTML = ''
  for (const r of results) {
    const div = document.createElement('div')
    div.className = 'result'
    const a = document.createElement('a')
    a.href = r.url
    a.target = '_blank'
    a.textContent = r.url
    div.appendChild(a)
    const save = document.createElement('button')
    save.textContent = 'Save'
    save.onclick = async ()=>{
      await fetch('/save', {method:'POST', headers:{'content-type':'application/json'}, body: JSON.stringify({url:r.url, title:null, meta:JSON.stringify(r)})})
      alert('Saved')
    }
    div.appendChild(save)
    el.appendChild(div)
  }
}

async function loadSaved(q=null) {
  const url = '/saved' + (q?('?query='+encodeURIComponent(q)):'')
  const resp = await fetch(url)
  const rows = await resp.json()
  const el = document.getElementById('saved')
  el.innerHTML = ''
  for (const r of rows) {
    const div = document.createElement('div')
    div.className = 'saved'
    const a = document.createElement('a')
    a.href = r.url
    a.target = '_blank'
    a.textContent = r.url
    div.appendChild(a)
    el.appendChild(div)
  }
}

document.getElementById('scan').onclick = scan
document.getElementById('clear').onclick = async ()=>{ await fetch('/clear_recent', {method:'POST'}); document.getElementById('results').innerHTML=''}
document.getElementById('searchSaved').onclick = ()=> loadSaved(document.getElementById('savedQuery').value.trim())

// load saved on start
loadSaved()
