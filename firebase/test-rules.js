// Testy reguł Firestore na emulatorze: kto może pisać i czytać.
const fs = require('fs');
const { initializeTestEnvironment, assertSucceeds, assertFails } = require('@firebase/rules-unit-testing');
const { doc, setDoc, getDoc, deleteDoc, getDocs, collection } = require('firebase/firestore');

const today = new Date().toISOString().slice(0, 10);
const good = (uid, extra = {}) => ({ uid, day: today, cell: '53.70:17.56', type: 'airtag', platform: 'android', v: 1, ...extra });
let failed = 0;
async function check(name, p) {
  try { await p; console.log('OK   ' + name); } catch (e) { failed++; console.log('BŁĄD ' + name + ': ' + e.message); }
}

(async () => {
  const env = await initializeTestEnvironment({
    projectId: 'demo-czuj',
    firestore: { rules: fs.readFileSync('firestore.rules', 'utf8'), host: '127.0.0.1', port: 8080 },
  });
  const alice = env.authenticatedContext('alice').firestore();
  const anon = env.unauthenticatedContext().firestore();
  const aid = `alice_${today}_airtag`;

  await check('zalogowany dopisuje poprawne zgłoszenie', assertSucceeds(setDoc(doc(alice, 'reports', aid), good('alice'))));
  await check('drugie zgłoszenie tego samego dnia i typu odrzucone', assertFails(setDoc(doc(alice, 'reports', aid), good('alice'))));
  await check('nikt nie czyta surowych zgłoszeń (nawet autor)', assertFails(getDoc(doc(alice, 'reports', aid))));
  await check('nie da się wylistować zgłoszeń', assertFails(getDocs(collection(alice, 'reports'))));
  await check('nie da się skasować zgłoszenia', assertFails(deleteDoc(doc(alice, 'reports', aid))));
  await check('niezalogowany nie pisze', assertFails(setDoc(doc(anon, 'reports', `x_${today}_airtag`), good('x'))));
  await check('nie można podszyć się pod innego', assertFails(setDoc(doc(alice, 'reports', `bob_${today}_airtag`), good('bob'))));
  await check('uid w treści musi być mój', assertFails(setDoc(doc(alice, 'reports', `alice_${today}_tile`), good('bob', { type: 'tile' }))));
  await check('dodatkowe pole odrzucone', assertFails(setDoc(doc(alice, 'reports', `alice_${today}_tile`), good('alice', { type: 'tile', mac: 'AA:BB' }))));
  await check('dokładna pozycja odrzucona', assertFails(setDoc(doc(alice, 'reports', `alice_${today}_tile`), good('alice', { type: 'tile', cell: '53.7012:17.5601' }))));
  await check('stara data odrzucona', assertFails(setDoc(doc(alice, 'reports', 'alice_2020-01-01_tile'), good('alice', { type: 'tile', day: '2020-01-01' }))));
  await check('nieznany typ odrzucony', assertFails(setDoc(doc(alice, 'reports', `alice_${today}_bomba`), good('alice', { type: 'bomba' }))));
  await check('zła wersja odrzucona', assertFails(setDoc(doc(alice, 'reports', `alice_${today}_tile`), good('alice', { type: 'tile', v: 2 }))));
  await check('drugi typ tego samego dnia przyjęty', assertSucceeds(setDoc(doc(alice, 'reports', `alice_${today}_tile`), good('alice', { type: 'tile' }))));

  await env.withSecurityRulesDisabled(c => setDoc(doc(c.firestore(), 'stats', 'week'), { json: '{}' }));
  await check('każdy czyta mapę zbiorczą', assertSucceeds(getDoc(doc(anon, 'stats', 'week'))));
  await check('nikt nie pisze mapy zbiorczej', assertFails(setDoc(doc(alice, 'stats', 'week'), { json: 'fałsz' })));
  await check('inne kolekcje zamknięte', assertFails(getDoc(doc(anon, 'inne', 'x'))));

  await env.cleanup();
  console.log(failed ? `\nBŁĘDÓW: ${failed}` : '\nWSZYSTKIE TESTY REGUŁ OK');
  process.exit(failed ? 1 : 0);
})();
