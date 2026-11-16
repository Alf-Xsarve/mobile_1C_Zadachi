import { readFileSync } from 'node:fs';
import { before, after, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, getDocs, collection, setDoc, updateDoc, deleteDoc, serverTimestamp, runTransaction } from 'firebase/firestore';

let env;
before(async () => {
  env = await initializeTestEnvironment({ projectId: 'demo-devtasks',
    firestore: { rules: readFileSync('firestore.rules', 'utf8') } });
});
after(async () => { await env?.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });
const db = uid => env.authenticatedContext(uid).firestore();
async function user(uid) {
  const store = db(uid);
  await setDoc(doc(store, 'users', uid), { name: uid, createdAt: serverTimestamp() });
  return store;
}
const task = uid => ({ title: 'Проверить общий доступ', description: 'Описание', status: 'new',
  authorId: uid, authorName: uid, assigneeId: '', assigneeName: '', createdAt: serverTimestamp(), updatedAt: serverTimestamp() });
async function claim(store, uid, id = 'one') {
  return runTransaction(store, async tx => {
    const ref = doc(store, 'tasks', id);
    const current = await tx.get(ref);
    if (current.data().status !== 'new') throw new Error('already assigned');
    tx.update(ref, { status: 'active', assigneeId: uid, assigneeName: uid, updatedAt: serverTimestamp() });
  });
}

test('Только зарегистрированные пользователи читают общие задачи', async () => {
  const a = await user('author');
  await assertSucceeds(setDoc(doc(a, 'tasks', 'one'), task('author')));
  await assertSucceeds(getDocs(collection(db('another'), 'tasks')));
  await assertFails(getDocs(collection(env.unauthenticatedContext().firestore(), 'tasks')));
});
test('Нельзя выдать себя за автора или создать задачу за другого исполнителя', async () => {
  const a = await user('author');
  await assertFails(setDoc(doc(a, 'tasks', 'bad'), { ...task('author'), authorId: 'other' }));
  await assertFails(setDoc(doc(a, 'tasks', 'bad'), { ...task('author'), authorName: 'Other Name' }));
  await assertFails(setDoc(doc(a, 'tasks', 'bad'), { ...task('author'), assigneeId: 'other' }));
});
test('Два пользователя одновременно берут задачу — выигрывает только один', async () => {
  const a = await user('alice');
  const b = await user('bob');
  await setDoc(doc(a, 'tasks', 'one'), task('alice'));
  const results = await Promise.allSettled([claim(a, 'alice'), claim(b, 'bob')]);
  assert.equal(results.filter(x => x.status === 'fulfilled').length, 1);
  const result = (await getDoc(doc(a, 'tasks', 'one'))).data();
  assert.equal(result.status, 'active');
  assert.ok(['alice', 'bob'].includes(result.assigneeId));
});
test('Чужую задачу нельзя перехватить или завершить; исполнитель может завершить свою', async () => {
  const a = await user('alice');
  const b = await user('bob');
  await setDoc(doc(a, 'tasks', 'one'), task('alice'));
  await claim(a, 'alice');
  await assertFails(updateDoc(doc(b, 'tasks', 'one'), { assigneeId: 'bob', assigneeName: 'bob', updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(doc(b, 'tasks', 'one'), { status: 'done', updatedAt: serverTimestamp() }));
  await assertSucceeds(updateDoc(doc(a, 'tasks', 'one'), { status: 'done', updatedAt: serverTimestamp() }));
});
test('Нельзя подменить имя исполнителя, изменить автора, описание или удалить задачу', async () => {
  const a = await user('alice');
  await setDoc(doc(a, 'tasks', 'one'), task('alice'));
  await assertFails(updateDoc(doc(a, 'tasks', 'one'), { status: 'active', assigneeId: 'alice', assigneeName: 'bob', updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(doc(a, 'tasks', 'one'), { authorId: 'bob' }));
  await assertFails(updateDoc(doc(a, 'tasks', 'one'), { description: 'Подмена' }));
  await assertFails(deleteDoc(doc(a, 'tasks', 'one')));
});
test('Профили нельзя читать списком или изменять за другого пользователя', async () => {
  const a = await user('alice');
  const b = await user('bob');
  await assertSucceeds(getDoc(doc(a, 'users', 'alice')));
  await assertFails(getDoc(doc(b, 'users', 'alice')));
  await assertFails(getDocs(collection(a, 'users')));
  await assertFails(updateDoc(doc(a, 'users', 'bob'), { name: 'Подмена' }));
});

const linkId = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const link = uid => ({ name: 'Инструкция', url: 'https://example.test/file.pdf', kind: 'file',
  authorId: uid, authorName: uid, createdAt: serverTimestamp() });
test('Авторизованные пользователи добавляют ссылки и читают материалы друг друга', async () => {
  const a = await user('alice');
  const b = await user('bob');
  await setDoc(doc(a, 'tasks', 'one'), task('alice'));
  await assertSucceeds(setDoc(doc(b, 'tasks', 'one', 'links', linkId), link('bob')));
  await assertSucceeds(getDocs(collection(a, 'tasks', 'one', 'links')));
  await assertFails(getDocs(collection(env.unauthenticatedContext().firestore(), 'tasks', 'one', 'links')));
});
test('Нельзя создать ссылку за другого автора или для несуществующей задачи', async () => {
  const a = await user('alice');
  await setDoc(doc(a, 'tasks', 'one'), task('alice'));
  await assertFails(setDoc(doc(a, 'tasks', 'one', 'links', linkId), link('bob')));
  await assertFails(setDoc(doc(a, 'tasks', 'one', 'links', linkId), { ...link('alice'), authorName: 'Bob' }));
  await assertFails(setDoc(doc(a, 'tasks', 'missing', 'links', linkId), link('alice')));
});
test('Ссылки допускают только HTTP(S), допустимые поля и виды материалов', async () => {
  const a = await user('alice');
  await setDoc(doc(a, 'tasks', 'one'), task('alice'));
  const ref = doc(a, 'tasks', 'one', 'links', linkId);
  for (const url of ['javascript:alert(1)', 'file:///private', 'https://u:p@example.test/x', 'https://example.test/a b']) {
    await assertFails(setDoc(ref, { ...link('alice'), url }));
  }
  await assertFails(setDoc(ref, { ...link('alice'), kind: 'script' }));
  await assertFails(setDoc(ref, { ...link('alice'), payload: 'base64 is not an attachment' }));
  for (const kind of ['image', 'file', 'web']) {
    const id = `${kind === 'image' ? 'b' : kind === 'web' ? 'c' : 'd'}aaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa`;
    await assertSucceeds(setDoc(doc(a, 'tasks', 'one', 'links', id), { ...link('alice'), kind }));
  }
});
test('Сохранённые материалы нельзя подменить или удалить', async () => {
  const a = await user('alice');
  const b = await user('bob');
  await setDoc(doc(a, 'tasks', 'one'), task('alice'));
  await setDoc(doc(a, 'tasks', 'one', 'links', linkId), link('alice'));
  await assertFails(updateDoc(doc(b, 'tasks', 'one', 'links', linkId), { url: 'https://evil.example/' }));
  await assertFails(deleteDoc(doc(a, 'tasks', 'one', 'links', linkId)));
});

test('Контакты WhatsApp и Telegram используют официальные HTTPS-домены', async () => {
  const a = await user('alice');
  await setDoc(doc(a, 'tasks', 'one'), task('alice'));
  const contact = (kind, url) => ({ ...link('alice'), kind, url });
  await assertSucceeds(setDoc(doc(a, 'tasks', 'one', 'links', linkId), contact('whatsapp', 'https://wa.me/996555123456')));
  const second = doc(a, 'tasks', 'one', 'links', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb');
  await assertSucceeds(setDoc(second, contact('telegram', 'https://t.me/+996555123456')));
  const bad = doc(a, 'tasks', 'one', 'links', 'cccccccc-cccc-cccc-cccc-cccccccccccc');
  for (const [kind, url] of [['telegram', 'https://evil.test/client'], ['whatsapp', 'https://wa.me.evil.test/client'],
    ['telegram', 'tg://resolve?domain=client'], ['whatsapp', 'http://wa.me/996555123456']]) {
    await assertFails(setDoc(bad, contact(kind, url)));
  }
});
