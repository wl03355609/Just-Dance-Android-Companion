const http = require("node:http");
const dgram = require("node:dgram");
const os = require("node:os");

const BOT_HOST = process.env.BOT_HOST || "127.0.0.1";
const BOT_PORT = numberValue(process.env.BOT_PORT, 3000);
const BRIDGE_HOST = process.env.BRIDGE_HOST || "0.0.0.0";
const BRIDGE_PORT_REQUESTED = Boolean(process.env.BRIDGE_PORT);
const DEFAULT_BRIDGE_PORT = 3000;
const FALLBACK_BRIDGE_PORT = 3001;
let bridgePort = numberValue(process.env.BRIDGE_PORT, DEFAULT_BRIDGE_PORT);
const DISCOVERY_REQUEST = "JUST_DANCE_REMOTE_DISCOVER_V1";
const DISCOVERY_RESPONSE = "JUST_DANCE_REMOTE_BRIDGE_V1";

const FORWARDED_PATHS = new Set([
  "/api/queue",
  "/api/songs",
  "/api/search",
  "/api/request",
  "/api/skip",
  "/api/remove",
  "/api/clear",
  "/api/filters",
  "/api/theme",
  "/api/pick",
  "/api/promote",
  "/events"
]);

const HOP_BY_HOP_HEADERS = new Set([
  "connection",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade"
]);

const server = http.createServer((request, response) => {
  let url;
  try {
    url = new URL(request.url, `http://${request.headers.host || "localhost"}`);
  } catch {
    return sendJson(response, 400, { ok: false, message: "Invalid request URL." });
  }

  if (request.method === "OPTIONS") {
    return sendCorsPreflight(response);
  }

  if (url.pathname === "/bridge/health") {
    return sendJson(response, 200, {
      ok: true,
      bridge: {
        host: BRIDGE_HOST,
        port: bridgePort,
        urls: lanUrls(bridgePort)
      },
      bot: {
        host: BOT_HOST,
        port: BOT_PORT,
        baseUrl: `http://${BOT_HOST}:${BOT_PORT}`
      }
    });
  }

  if (!FORWARDED_PATHS.has(url.pathname)) {
    return sendJson(response, 404, {
      ok: false,
      message: "This bridge only forwards the bot API and event stream."
    });
  }

  forwardToBot(request, response);
});

server.on("clientError", (_error, socket) => {
  socket.end("HTTP/1.1 400 Bad Request\r\n\r\n");
});

server.on("error", (error) => {
  console.error(`Could not start the bridge on ${BRIDGE_HOST}:${bridgePort}: ${error.message}`);
  if (error.code === "EADDRINUSE") {
    if (!BRIDGE_PORT_REQUESTED && bridgePort === DEFAULT_BRIDGE_PORT) {
      console.error(`Port ${DEFAULT_BRIDGE_PORT} is busy. Trying ${FALLBACK_BRIDGE_PORT} instead.`);
      listen(FALLBACK_BRIDGE_PORT);
      return;
    }
    console.error("Try another port, for example BRIDGE_PORT=3001 npm start.");
  } else if (error.code === "EACCES" || error.code === "EPERM") {
    console.error("Try running from a normal terminal, or set BRIDGE_HOST=127.0.0.1 for local-only testing.");
  }
  process.exitCode = 1;
});

listen(bridgePort);

function listen(port) {
  bridgePort = port;
  server.listen(bridgePort, BRIDGE_HOST, onBridgeListening);
}

function onBridgeListening() {
  console.log(`Just Dance remote bridge listening on ${BRIDGE_HOST}:${bridgePort}`);
  console.log(`Forwarding to bot at http://${BOT_HOST}:${BOT_PORT}`);
  startDiscoveryResponder();
  console.log("");
  console.log("Use one of these URLs in the Android app:");
  for (const url of lanUrls(bridgePort)) {
    console.log(`  ${url}`);
  }
  console.log("");
  console.log("Health check:");
  console.log(`  http://127.0.0.1:${bridgePort}/bridge/health`);
}

function startDiscoveryResponder() {
  const socket = dgram.createSocket("udp4");

  socket.on("message", (message, remote) => {
    if (message.toString("utf8").trim() !== DISCOVERY_REQUEST) return;

    const response = Buffer.from(JSON.stringify({
      kind: DISCOVERY_RESPONSE,
      baseUrl: bestLanUrlForRemote(remote.address, bridgePort),
      urls: lanUrls(bridgePort),
      bot: {
        host: BOT_HOST,
        port: BOT_PORT,
        baseUrl: `http://${BOT_HOST}:${BOT_PORT}`
      }
    }));

    socket.send(response, remote.port, remote.address);
  });

  socket.on("error", (error) => {
    console.error(`Bridge discovery responder failed on UDP ${bridgePort}: ${error.message}`);
  });

  socket.bind(bridgePort, "0.0.0.0", () => {
    socket.setBroadcast(true);
    console.log(`Discovery responder listening on UDP ${bridgePort}`);
  });
}

function forwardToBot(clientRequest, clientResponse) {
  const headers = cleanProxyHeaders(clientRequest.headers);
  headers.host = `${BOT_HOST}:${BOT_PORT}`;

  const botRequest = http.request({
    host: BOT_HOST,
    port: BOT_PORT,
    method: clientRequest.method,
    path: clientRequest.url,
    headers
  }, (botResponse) => {
    const responseHeaders = cleanProxyHeaders(botResponse.headers);
    responseHeaders["access-control-allow-origin"] = "*";
    responseHeaders["access-control-allow-headers"] = "Content-Type, X-Queue-Admin, Authorization";
    responseHeaders["access-control-allow-methods"] = "GET, POST, OPTIONS";

    clientResponse.writeHead(botResponse.statusCode || 502, responseHeaders);
    botResponse.pipe(clientResponse);
  });

  botRequest.on("error", (error) => {
    if (clientResponse.headersSent) {
      clientResponse.end();
      return;
    }

    sendJson(clientResponse, 502, {
      ok: false,
      message: `Could not reach the bot at http://${BOT_HOST}:${BOT_PORT}: ${error.message}`
    });
  });

  clientRequest.pipe(botRequest);
}

function cleanProxyHeaders(headers) {
  const next = {};
  for (const [key, value] of Object.entries(headers)) {
    if (!HOP_BY_HOP_HEADERS.has(key.toLowerCase())) next[key] = value;
  }
  return next;
}

function sendCorsPreflight(response) {
  response.writeHead(204, {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Content-Type, X-Queue-Admin, Authorization",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Max-Age": "86400"
  });
  response.end();
}

function sendJson(response, status, data) {
  response.writeHead(status, {
    "Content-Type": "application/json",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Content-Type, X-Queue-Admin, Authorization",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "X-Content-Type-Options": "nosniff"
  });
  response.end(JSON.stringify(data, null, 2));
}

function lanUrls(port) {
  const urls = [`http://127.0.0.1:${port}`];
  for (const addresses of Object.values(os.networkInterfaces())) {
    for (const address of addresses || []) {
      if (address.family === "IPv4" && !address.internal) {
        urls.push(`http://${address.address}:${port}`);
      }
    }
  }
  return [...new Set(urls)];
}

function bestLanUrlForRemote(remoteAddress, port) {
  const urls = lanUrls(port);
  const remotePrefix = ipv4Prefix(remoteAddress);
  if (remotePrefix) {
    for (const url of urls) {
      try {
        const host = new URL(url).hostname;
        if (ipv4Prefix(host) === remotePrefix) return url;
      } catch {
        // Ignore malformed URLs; lanUrls controls this list.
      }
    }
  }
  return urls.find((url) => !url.includes("127.0.0.1")) || urls[0];
}

function ipv4Prefix(address) {
  if (!/^\d+\.\d+\.\d+\.\d+$/.test(address)) return "";
  return address.split(".").slice(0, 3).join(".") + ".";
}

function numberValue(value, fallback) {
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}
