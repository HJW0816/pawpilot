let session;
const status = document.querySelector('#status');
async function request(path, body) {
  const response = await fetch(path, { method: 'POST', headers: {'Content-Type': 'application/json', ...(session ? {Authorization: `Bearer ${session.guest}`} : {})}, body: JSON.stringify(body), signal: AbortSignal.timeout(8000) });
  if (!response.ok) throw Error('연결이 만료되었거나 요청을 처리할 수 없어요. 차량에서 새로 연결해 주세요.');
  return response.json();
}
function node(tag, text, parent) { const e = document.createElement(tag); if (text) e.textContent = text; parent.append(e); return e; }
document.querySelector('#join').addEventListener('submit', async event => {
  event.preventDefault(); const button = event.target.querySelector('button'); button.disabled = true;
  try {
    session = await request('/v1/companion/join', {code: document.querySelector('#code').value.replaceAll('-', '').trim().toLowerCase()});
    event.target.hidden = true; status.textContent = '연결됐어요. 후보를 선택해 주세요. 방향·운영 여부는 내비와 현장에서 다시 확인하세요.';
    for (const candidate of session.candidates) {
      const card = node('article', '', document.querySelector('#candidates')); node('h2', candidate.name, card);
      node('small', `${session.source} · ${session.asOf} 자료 기준 (영업 확인일 아님)`, card);
      for (const f of candidate.facilities) node('p', `${f.kind} · ${f.location}\n운영 ${f.hours} / 휴장 ${f.closed}`, card);
      const form = node('form', '', card), label = node('label', '방문 별점 초안 (선택)', form), stars = node('select', '', label);
      ['방문 별점 없음','★ 1','★ 2','★ 3','★ 4','★ 5'].forEach((text, index) => { const o = node('option', text, stars); o.value = index; });
      const commentLabel = node('label', '동승자 메모·후기 초안 (80자 이내)', form), comment = node('textarea', '', commentLabel); comment.maxLength = 80;
      const send = node('button', '이 후보를 차량으로 보내기', form);
      form.addEventListener('submit', async event => { event.preventDefault(); send.disabled = true;
        try { await request(`/v1/companion/${session.id}/suggest`, {name: candidate.name, stars: Number(stars.value), comment: comment.value}); status.textContent = '후보를 보냈어요. 차량에서 확인해야 검색이 진행됩니다. 공개 후기로 등록되지 않았습니다.'; }
        catch (error) { status.textContent = error.message; } finally { send.disabled = false; }
      });
    }
    setTimeout(() => { session = null; document.querySelector('#candidates').replaceChildren(); status.textContent = '연결 시간이 끝났어요. 차량에서 새 연결을 만들어 주세요.'; }, Math.max(0, session.expiresAt - Date.now()));
  } catch (error) { status.textContent = error.message; } finally { button.disabled = false; }
});
