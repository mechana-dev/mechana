package dev.mechana.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Small dependency-free live dashboard for one local video job. */
public final class VideoJobDashboardServer implements AutoCloseable {
	private final ObjectMapper json = new ObjectMapper();
	private final VideoJobMonitor monitor;
	private final HttpServer server;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "The shared thread-safe monitor is the dashboard's live data source")
	public VideoJobDashboardServer(int port, VideoJobMonitor monitor) throws IOException {
		this.monitor = monitor;
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		server.createContext("/", this::serveDashboard);
		server.createContext("/api/status", this::serveStatus);
		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
	}

	public void start() {
		server.start();
	}

	public int port() {
		return server.getAddress().getPort();
	}

	@Override
	public void close() {
		server.stop(0);
	}

	private void serveDashboard(HttpExchange exchange) throws IOException {
		if (!"GET".equals(exchange.getRequestMethod()) || !"/".equals(exchange.getRequestURI().getPath())) {
			send(exchange, 404, "text/plain", "Not found");
			return;
		}
		send(exchange, 200, "text/html; charset=utf-8", HTML);
	}

	private void serveStatus(HttpExchange exchange) throws IOException {
		if (!"GET".equals(exchange.getRequestMethod())) {
			send(exchange, 405, "text/plain", "GET required");
			return;
		}
		byte[] content = json.writeValueAsBytes(monitor.snapshot());
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(200, content.length);
		try (var output = exchange.getResponseBody()) {
			output.write(content);
		}
	}

	private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
		byte[] content = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", type);
		exchange.sendResponseHeaders(status, content.length);
		try (var output = exchange.getResponseBody()) {
			output.write(content);
		}
	}

	private static final String HTML = """
			<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
			<title>Mechana Video Job</title><style>
			:root{color-scheme:dark;font-family:ui-sans-serif,system-ui;background:#0b1020;color:#e7ecf5}body{max-width:1200px;margin:0 auto;padding:28px}
			h1{margin:0 0 4px}.muted{color:#91a0b8}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;margin:22px 0}.card,section{background:#141b2d;border:1px solid #26324a;border-radius:12px;padding:16px}.value{font-size:28px;font-weight:700}.bar{height:14px;background:#273249;border-radius:9px;overflow:hidden}.fill{height:100%;background:linear-gradient(90deg,#25c2a0,#5b8cff);transition:width .4s}table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:9px;border-bottom:1px solid #26324a}th{color:#91a0b8}.RUNNING{color:#58a6ff}.SUCCEEDED{color:#3ddc97}.FAILED{color:#ff6b6b}code{word-break:break-all}ul{max-height:230px;overflow:auto;padding-left:20px}
			</style></head><body><h1>Mechana Video Job</h1><div class="muted" id="paths"></div>
			<div class="cards"><div class="card"><div class="muted">Stage</div><div class="value" id="stage">—</div></div><div class="card"><div class="muted">Overall</div><div class="value" id="overall">0%</div></div><div class="card"><div class="muted">Workers</div><div class="value" id="workers">0 / 0</div></div><div class="card"><div class="muted">Elapsed</div><div class="value" id="elapsed">00:00:00</div></div></div>
			<section><div class="bar"><div class="fill" id="overallBar"></div></div><p id="summary"></p><p class="FAILED" id="error"></p></section>
			<section><h2>Segments</h2><table><thead><tr><th>#</th><th>Range</th><th>State</th><th>Progress</th><th>Elapsed</th></tr></thead><tbody id="segments"></tbody></table></section>
			<section><h2>Recent events</h2><ul id="events"></ul></section><script>
			const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
			async function refresh(){try{const s=await fetch('/api/status',{cache:'no-store'}).then(r=>r.json());stage.textContent=s.stage;overall.textContent=s.progress+'%';workers.textContent=s.activeWorkers+' / '+s.configuredWorkers;elapsed.textContent=s.elapsed;overallBar.style.width=s.progress+'%';summary.textContent=`${s.completedSegments} of ${s.totalSegments} segments complete`;error.textContent=s.error||'';paths.innerHTML=`Input: <code>${esc(s.input)}</code><br>Output: <code>${esc(s.output)}</code>`;segments.innerHTML=s.segments.map(x=>`<tr><td>${x.index}</td><td>${x.startSeconds.toFixed(1)}–${x.endSeconds.toFixed(1)}s</td><td class="${x.state}">${x.state}</td><td><div class="bar"><div class="fill" style="width:${x.progress}%"></div></div> ${x.progress}%</td><td>${x.elapsed}</td></tr>`).join('');events.innerHTML=s.events.map(e=>`<li>${esc(e)}</li>`).join('');}catch(e){error.textContent=e;}setTimeout(refresh,1000)}refresh();
			</script></body></html>
			""";
}
