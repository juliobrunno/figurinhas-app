// Partida do robô do Figuritas Zap (o robô de verdade fica em robo.mjs).
// No celular, se o Node.js fechar, o processo inteiro fecha. Por isso aqui:
//  - todo erro vai para o registro robo-dados/robo-log.txt (o app mostra esse registro na tela);
//  - pedidos para fechar o Node são ignorados;
//  - se o robô não carregar, um servidor reserva responde com o erro.
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';

const DATA = path.resolve(process.argv[2] || './dados');
fs.mkdirSync(DATA, { recursive: true });
const LOGF = path.join(DATA, 'robo-log.txt');
try { if (fs.statSync(LOGF).size > 200 * 1024) fs.writeFileSync(LOGF, ''); } catch { }

const fmt = a => a instanceof Error ? (a.stack || a.message) : typeof a === 'object' ? (() => { try { return JSON.stringify(a); } catch { return String(a); } })() : String(a);
function roboLog(...a) {
	const line = `[${new Date().toISOString()}] [robo] ${a.map(fmt).join(' ')}\n`;
	try { fs.appendFileSync(LOGF, line); } catch { }
	try { process.stdout.write(line); } catch { }
}
globalThis.__roboLog = roboLog;

process.on('uncaughtException', e => roboLog('erro solto:', e));
process.on('unhandledRejection', e => roboLog('promessa rejeitada:', e));
process.exit = code => { roboLog(`algo pediu para fechar o robô (código ${code}); ignorado`); };
setInterval(() => { }, 1 << 30); // mantém o robô ligado

roboLog(`Node ${process.version} em ${process.platform}/${process.arch}, iniciando`);
try {
	await import('./robo.mjs');
	roboLog('robô carregado');
} catch (e) {
	roboLog('o robô não carregou:', e);
	const msg = 'O robô não carregou: ' + (e && e.message || e);
	http.createServer((req, res) => {
		res.writeHead(req.url === '/status' ? 200 : 500, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
		res.end(JSON.stringify({ state: 'erro', error: msg, code: null, me: null }));
	}).listen(47321, '127.0.0.1');
}
