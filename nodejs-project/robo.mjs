// Robô do Figuritas Zap.
// Roda dentro do APK (nodejs-mobile) e abre um pequeno servidor só no próprio celular (127.0.0.1:47321).
// A tela do app conversa com ele para: conectar ao WhatsApp com código de 8 letras e enviar
// figurinhas com som para o próprio número (conversa "Você"), de onde dá para favoritar.
//
// Atenção: usa um cliente NÃO oficial do WhatsApp (Baileys). Uso pessoal, por conta e risco do dono.
// Formato da figurinha com som (descoberto por engenharia reversa, pode mudar):
//   Message.134 (FutureProofMessage) > .1 (Message) > .26 (StickerMessage) > .26 (AudioMessage)
import './polyfills.mjs';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const PORT = 47321;
const DATA = path.resolve(process.argv[2] || './dados');
const AUTH = path.join(DATA, 'whatsapp-login');
fs.mkdirSync(DATA, { recursive: true });

const B = await import('@whiskeysockets/baileys');
const makeWASocket = B.default?.default || B.default || B.makeWASocket;
const { useMultiFileAuthState, DisconnectReason, Browsers, fetchLatestBaileysVersion, prepareWAMessageMedia,
	generateWAMessageFromContent, getMediaKeys, jidNormalizedUser, proto } = B;
const pino = (await import('pino')).default;
const logger = pino({ level: 'warn' });

/* ---------- estado ---------- */
const S = { state: 'desconectado', code: null, me: null, error: null };
let sock = null;
let pairPhone = null;
let codeWaiter = null;

const log = (...a) => (globalThis.__roboLog || console.log)(...a);

async function start() {
	if (sock) { try { sock.end(undefined); } catch { } sock = null; }
	const { state, saveCreds } = await useMultiFileAuthState(AUTH);
	let version;
	try { version = (await fetchLatestBaileysVersion()).version; } catch { }
	S.state = 'conectando'; S.error = null;
	const s = makeWASocket({
		auth: state, logger, version, browser: Browsers.macOS('Chrome'),
		printQRInTerminal: false, markOnlineOnConnect: false, syncFullHistory: false,
		shouldSyncHistoryMessage: () => false, generateHighQualityLinkPreview: false
	});
	sock = s;
	s.ev.on('creds.update', saveCreds);
	s.ev.on('connection.update', async u => {
		if (sock !== s) return;
		const { connection, lastDisconnect, qr } = u;
		if (qr && pairPhone && !state.creds.registered && !S.code) {
			try {
				S.code = await s.requestPairingCode(pairPhone);
				S.state = 'aguardando_codigo';
				log('código de pareamento gerado');
				codeWaiter?.resolve(S.code);
			} catch (e) {
				S.error = 'Não consegui pedir o código: ' + e.message;
				codeWaiter?.reject(e);
			}
			codeWaiter = null;
		}
		if (connection === 'open') {
			S.state = 'conectado'; S.code = null; pairPhone = null; S.error = null;
			S.me = jidNormalizedUser(s.user?.id || '');
			log('conectado como', S.me);
		}
		if (connection === 'close') {
			const status = lastDisconnect?.error?.output?.statusCode;
			log('conexão fechou', status);
			if (status === DisconnectReason.loggedOut) {
				fs.rmSync(AUTH, { recursive: true, force: true });
				Object.assign(S, { state: 'desconectado', code: null, me: null, error: 'O WhatsApp desconectou o robô. Conecte de novo.' });
				sock = null;
				return;
			}
			if (!state.creds.registered && !pairPhone) { S.state = 'desconectado'; sock = null; return; }
			setTimeout(() => { if (sock === s) start().catch(e => { S.error = e.message; }); }, status === DisconnectReason.restartRequired ? 500 : 3000);
		}
	});
	return s;
}

/* ---------- figurinha com som ---------- */
const WRAP = Symbol.for('figuritas.somWrapper');
let encoderInstalled = false;
function installEncoder() {
	if (encoderInstalled) return;
	encoderInstalled = true;
	const original = proto.Message.encode.bind(proto.Message);
	proto.Message.encode = function (message, writer) {
		const wrapper = message && message[WRAP];
		if (!wrapper) return original(message, writer);
		const copy = { ...message };
		delete copy.stickerMessage;
		const w = original(copy, writer);
		w.uint32((134 << 3) | 2).bytes(wrapper);
		return w;
	};
}
function varint(n) { const out = []; while (n > 127) { out.push((n & 127) | 128); n >>>= 7; } out.push(n); return Buffer.from(out); }
const lengthField = (field, bytes) => Buffer.concat([varint((field << 3) | 2), varint(bytes.length), Buffer.from(bytes)]);

async function audioSidecar(plain, mediaKey) {
	const { iv, cipherKey, macKey } = await getMediaKeys(Buffer.from(mediaKey), 'audio');
	const c = crypto.createCipheriv('aes-256-cbc', cipherKey, iv);
	const enc = Buffer.concat([c.update(plain), c.final()]);
	const parts = [];
	for (let off = 0; off < enc.length; off += 65536) {
		const prev = off === 0 ? iv : enc.subarray(off - 16, off);
		parts.push(crypto.createHmac('sha256', macKey).update(prev).update(enc.subarray(off, off + 65536)).digest().subarray(0, 10));
	}
	return Buffer.concat(parts);
}

async function sendSoundSticker({ webp, ogg, seconds, animated }) {
	if (!sock || S.state !== 'conectado') throw new Error('O robô não está conectado ao WhatsApp.');
	installEncoder();
	const jid = S.me;
	const opts = { upload: sock.waUploadToServer, mediaUploadTimeoutMs: 60000, logger };
	const vis = await prepareWAMessageMedia({ sticker: webp, isAnimated: !!animated, width: 512, height: 512 }, opts);
	const aud = await prepareWAMessageMedia({ audio: ogg, mimetype: 'audio/ogg; codecs=opus', seconds: Math.max(1, Math.ceil(seconds || 1)) }, opts);
	const audio = aud.audioMessage;
	try { audio.streamingSidecar = await audioSidecar(ogg, audio.mediaKey); } catch (e) { log('sidecar falhou', e.message); }
	const sticker = vis.stickerMessage;
	sticker.isAnimated = !!animated;
	sticker.width = 512; sticker.height = 512;
	const stickerBytes = Buffer.concat([
		Buffer.from(proto.Message.StickerMessage.encode(sticker).finish()),
		lengthField(26, proto.Message.AudioMessage.encode(audio).finish())
	]);
	const wrapper = lengthField(1, lengthField(26, stickerBytes));
	const msg = generateWAMessageFromContent(jid, { stickerMessage: sticker }, { userJid: sock.user.id });
	msg.message[WRAP] = wrapper;
	await sock.relayMessage(jid, msg.message, { messageId: msg.key.id });
	log('figurinha com som enviada');
	return { id: msg.key.id };
}

/* ---------- servidor local ---------- */
function reply(res, code, obj) {
	res.writeHead(code, {
		'Content-Type': 'application/json; charset=utf-8',
		'Access-Control-Allow-Origin': '*',
		'Access-Control-Allow-Headers': 'Content-Type',
		'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
		'Access-Control-Allow-Private-Network': 'true'
	});
	res.end(JSON.stringify(obj));
}
const readBody = req => new Promise((ok, bad) => {
	const chunks = []; let n = 0;
	req.on('data', c => { n += c.length; if (n > 8 * 1024 * 1024) { bad(new Error('arquivo grande demais')); req.destroy(); } else chunks.push(c); });
	req.on('end', () => { try { ok(chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {}); } catch (e) { bad(e); } });
	req.on('error', bad);
});

const server = http.createServer(async (req, res) => {
	if (req.method === 'OPTIONS') return reply(res, 204, {});
	const url = new URL(req.url, 'http://x');
	try {
		if (url.pathname === '/status') return reply(res, 200, { ...S, versao: 1 });
		if (req.method !== 'POST') return reply(res, 404, { error: 'não encontrado' });
		const body = await readBody(req);
		if (url.pathname === '/pair') {
			const phone = String(body.phone || '').replace(/\D/g, '');
			if (phone.length < 10) return reply(res, 400, { error: 'Número inválido. Use DDI + DDD + número, ex.: 5511999998888' });
			fs.rmSync(AUTH, { recursive: true, force: true });
			pairPhone = phone; S.code = null; S.me = null;
			const p = new Promise((resolve, reject) => { codeWaiter = { resolve, reject }; });
			await start();
			const code = await Promise.race([p, new Promise((_, r) => setTimeout(() => r(new Error('O WhatsApp demorou para responder. Tente de novo.')), 30000))]);
			return reply(res, 200, { code });
		}
		if (url.pathname === '/send') {
			const webp = Buffer.from(String(body.webp || ''), 'base64');
			const ogg = Buffer.from(String(body.ogg || ''), 'base64');
			if (!webp.length || !ogg.length) return reply(res, 400, { error: 'Faltou a figurinha ou o som.' });
			const r = await sendSoundSticker({ webp, ogg, seconds: +body.seconds, animated: !!body.animated });
			return reply(res, 200, { ok: true, ...r });
		}
		if (url.pathname === '/logout') {
			try { await sock?.logout(); } catch { }
			fs.rmSync(AUTH, { recursive: true, force: true });
			sock = null; pairPhone = null;
			Object.assign(S, { state: 'desconectado', code: null, me: null, error: null });
			return reply(res, 200, { ok: true });
		}
		return reply(res, 404, { error: 'não encontrado' });
	} catch (e) {
		log('erro', e);
		return reply(res, 500, { error: e.message || String(e) });
	}
});
server.listen(PORT, '127.0.0.1', () => log('servidor pronto na porta', PORT, 'dados em', DATA));

// Se já estava conectado antes, reconecta sozinho
if (fs.existsSync(path.join(AUTH, 'creds.json'))) {
	try {
		const creds = JSON.parse(fs.readFileSync(path.join(AUTH, 'creds.json'), 'utf8'));
		if (creds.registered) start().catch(e => { S.error = e.message; });
	} catch { }
}
// (os avisos de erro solto ficam no main.mjs)

