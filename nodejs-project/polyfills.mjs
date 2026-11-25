// O Node.js do celular (nodejs-mobile) é a versão 18. O Baileys 7 pede Node 20+,
// então completamos aqui o que falta no 18.
import { webcrypto } from 'node:crypto';

if (!globalThis.crypto) globalThis.crypto = webcrypto;

if (!Promise.withResolvers) {
	Promise.withResolvers = function () {
		let resolve, reject;
		const promise = new this((a, b) => { resolve = a; reject = b; });
		return { promise, resolve, reject };
	};
}

const arr = Array.prototype;
if (!arr.toSorted) Object.defineProperty(arr, 'toSorted', { value(fn) { return this.slice().sort(fn); }, configurable: true, writable: true });
if (!arr.toReversed) Object.defineProperty(arr, 'toReversed', { value() { return this.slice().reverse(); }, configurable: true, writable: true });
if (!arr.toSpliced) Object.defineProperty(arr, 'toSpliced', { value(...a) { const c = this.slice(); c.splice(...a); return c; }, configurable: true, writable: true });
if (!arr.with) Object.defineProperty(arr, 'with', { value(i, v) { const c = this.slice(); c[i < 0 ? c.length + i : i] = v; return c; }, configurable: true, writable: true });
if (!arr.findLast) Object.defineProperty(arr, 'findLast', { value(fn) { for (let i = this.length - 1; i >= 0; i--) if (fn(this[i], i, this)) return this[i]; }, configurable: true, writable: true });
if (!Object.groupBy) Object.groupBy = (items, fn) => { const o = Object.create(null); let i = 0; for (const it of items) (o[fn(it, i++)] ||= []).push(it); return o; };
if (!Map.groupBy) Map.groupBy = (items, fn) => { const m = new Map(); let i = 0; for (const it of items) { const k = fn(it, i++); if (!m.has(k)) m.set(k, []); m.get(k).push(it); } return m; };
if (!Set.prototype.union) Object.defineProperty(Set.prototype, 'union', { value(o) { return new Set([...this, ...o]); }, configurable: true, writable: true });
if (!Set.prototype.difference) Object.defineProperty(Set.prototype, 'difference', { value(o) { const s = new Set(o); return new Set([...this].filter(x => !s.has(x))); }, configurable: true, writable: true });
if (!Set.prototype.intersection) Object.defineProperty(Set.prototype, 'intersection', { value(o) { const s = new Set(o); return new Set([...this].filter(x => s.has(x))); }, configurable: true, writable: true });
