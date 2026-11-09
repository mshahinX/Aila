import { action, type Action, type Next } from './protocol.js';

export function containsSensitiveData(command: string, clarifications: string[], request: Next): boolean {
  const values = [command, ...clarifications, request.previousResult ?? '', ...request.observation.nodes.flatMap(n => [n.text, n.description])];
  // Defense in depth, not a substitute for native credential-screen filtering.
  if (values.some(v => /(?:\d[ -]?){13,}/.test(v))) return true;
  return request.observation.nodes.some(n => /password|passwd|(?:^|[_:/])otp|cvv|cvc|pin_code|pin_entry|one.?time/i.test(n.resourceId) || /^(password|passcode|PIN|OTP|CVV|CVC|verification code)$/i.test(n.text.trim()));
}
export function manualBoundary(request: Next): Action | undefined {
  const o = request.observation;
  if (o.packageName === 'az.kapitalbank.mbanking') return action('manual', { message: 'Real banking screens are unverified. Prepare and confirm manually in the bank app; no transaction is verified.' });
  if (o.screen === 'manual') return action('manual', { message: 'Continue manually; resume only after explicit user interaction.' });
  if (o.packageName === 'dev.personal.phoneagent' && !(o.screen === 'fixture' || (o.screen === 'unavailable' && o.nodes.length === 0))) return action('manual', { message: 'Agent controls and approval UI cannot be automated.' });
  return undefined;
}
export function mockPlan(command: string, request: Next): Action {
  const o = request.observation;
  if (o.packageName !== 'dev.personal.phoneagent' || o.screen !== 'fixture') {
    if (o.packageName === 'dev.personal.phoneagent' && o.screen === 'unavailable' && o.nodes.length === 0 && /\b(fixture|test)\b/i.test(command)) return action('launch', { packageName: 'dev.personal.phoneagent' });
    return action('manual', { message: 'Mock mode operates only the synthetic fixture. Real apps require separately enabled live mode.' });
  }
  const find = (name: string) => o.nodes.find(n => n.resourceId === `dev.personal.phoneagent:id/${name}`);
  const status = find('fixture_status');
  if (status?.text === 'Delivered') return action('finish', { message: 'Synthetic fixture reports Delivered. No real message was sent.' });
  const entry = find('fixture_entry');
  // Demo plans may name the synthetic draft as draft: "…"; still only the synthetic fixture is operated.
  const draft = /\bdraft:\s*"([^"\n]{1,500})"/.exec(command)?.[1] ?? 'hello';
  if (entry?.enabled && entry.editable) {
    if (entry.text !== '' && entry.text !== draft) return action('ask_user', { question: 'The fixture already contains a different draft. Preserve it or clear it manually before continuing.' });
    if (entry.text !== draft) return action('set_text', { targetId: entry.id, text: draft });
    const send = find('fixture_send');
    if (send?.enabled && send.clickable) {
      if (request.previousResult && /attempt|uncertain|unknown|timeout|dispatch/i.test(request.previousResult)) return action('manual', { message: 'A previous action may have been dispatched. Inspect the fixture; do not automatically retry Send.' });
      return action('click', { targetId: send.id });
    }
  }
  const search = find('fixture_search');
  if (search?.enabled && search.editable && search.text !== 'Alex') return action('set_text', { targetId: search.id, text: 'Alex' });
  const contact = find('fixture_contact_alex');
  if (contact?.enabled && contact.clickable && search?.text === 'Alex') return action('click', { targetId: contact.id });
  return action('manual', { message: 'Synthetic fixture state is not recognized. Open/reset the fixture manually.' });
}
