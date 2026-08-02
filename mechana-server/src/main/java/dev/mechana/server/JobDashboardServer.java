package dev.mechana.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mechana.coordinator.InMemoryJobMonitor;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Small dependency-free live dashboard for one observable job. */
public final class JobDashboardServer implements AutoCloseable {
	private final ObjectMapper json = new ObjectMapper();
	private final InMemoryJobMonitor monitor;
	private final HttpServer server;

	@SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "The shared thread-safe monitor is the dashboard's live data source")
	public JobDashboardServer(int port, InMemoryJobMonitor monitor) throws IOException {
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
		send(exchange, 200, "text/html; charset=utf-8", dashboardHtml("/api/status"));
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
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(status, content.length);
		try (var output = exchange.getResponseBody()) {
			output.write(content);
		}
	}

	static String dashboardHtml(String statusUrl) {
		return dashboardHtml(statusUrl, "");
	}

	static String dashboardHtml(String statusUrl, String serverDashboardUrl) {
		String navigation = serverDashboardUrl.isBlank()
				? ""
				: "<a href=\"" + serverDashboardUrl + "\">← Server dashboard</a>";
		return HTML.replace("__STATUS_URL__", statusUrl).replace("__NAV__", navigation);
	}

	private static final String HTML = """
			<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
			<title>Mechana Job</title><style>
			:root{color-scheme:dark;font-family:ui-sans-serif,system-ui;background:#0b1020;color:#e7ecf5}body{max-width:1200px;margin:0 auto;padding:28px}
			h1{margin:0 0 4px}.muted{color:#91a0b8}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;margin:22px 0}.card,section{background:#141b2d;border:1px solid #26324a;border-radius:12px;padding:16px;margin-bottom:16px}.value{font-size:28px;font-weight:700}.bar{height:14px;background:#273249;border-radius:9px;overflow:hidden}.fill{height:100%;background:linear-gradient(90deg,#25c2a0,#5b8cff);transition:width .4s}table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:9px;border-bottom:1px solid #26324a}th{color:#91a0b8}.RUNNING{color:#58a6ff}.SUCCEEDED{color:#3ddc97}.FAILED,.CANCELLED{color:#ff6b6b}code{word-break:break-all}ul{max-height:230px;overflow:auto;padding-left:20px}a{color:#75a7ff}button{background:#7c2d3a;color:#fff;border:1px solid #b94b5d;border-radius:7px;padding:8px 12px;cursor:pointer}
			</style></head><body><nav>__NAV__</nav><h1>Mechana Job</h1><div class="muted" id="identity"></div>
			<div class="cards"><div class="card"><div class="muted">Stage</div><div class="value" id="stage">—</div></div><div class="card"><div class="muted">Overall</div><div class="value" id="overall">0%</div></div><div class="card"><div class="muted">Workers</div><div class="value" id="workers">0 / 0</div></div><div class="card"><div class="muted">Elapsed</div><div class="value" id="elapsed">00:00:00</div></div></div>
			<section><div class="bar"><div class="fill" id="overallBar"></div></div><p id="summary"></p><div id="jobDetails"></div><p><button id="abortJobButton" hidden>Abort job</button></p><p class="FAILED" id="error"></p></section>
			<section><h2>Work units</h2><table><thead><tr><th>Work unit</th><th>Details</th><th>Worker</th><th>State</th><th>Progress</th><th>Elapsed</th></tr></thead><tbody id="workUnits"></tbody></table></section>
			<section id="artifactSection" hidden><h2>Artifacts</h2><div class="muted">These files remain available after a server restart.</div><ul id="artifacts"></ul><button id="purge" hidden>Purge job and artifacts</button></section>
			<section><h2>Recent events</h2><ul id="events"></ul></section><script>
			const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
			const fields=o=>Object.entries(o||{}).map(([k,v])=>`<span><b>${esc(k)}:</b> ${esc(v)}</span>`).join('<br>');
			const size=n=>n<1024?n+' B':n<1048576?(n/1024).toFixed(1)+' KB':(n/1048576).toFixed(1)+' MB';
			async function refresh(){try{const s=await fetch('__STATUS_URL__',{cache:'no-store'}).then(r=>r.json());stage.textContent=s.stage;overall.textContent=s.progress+'%';workers.textContent=s.activeWorkers+' / '+s.configuredWorkers;elapsed.textContent=s.elapsed;overallBar.style.width=s.progress+'%';summary.textContent=`${s.completedWorkUnits} of ${s.totalWorkUnits} work units complete`;error.textContent=s.error||'';identity.innerHTML=`Job: <code>${esc(s.jobId)}</code> · Plugin: <code>${esc(s.plugin)}</code>`;jobDetails.innerHTML=fields(s.details);workUnits.innerHTML=s.workUnits.map(x=>`<tr><td><b>${esc(x.label)}</b><br><code>${esc(x.id)}</code></td><td>${fields(x.details)}</td><td><code>${esc(x.workerAddress)}</code></td><td class="${x.state}">${x.state}</td><td><div class="bar"><div class="fill" style="width:${x.progress}%"></div></div> ${x.progress}%</td><td>${x.elapsed}</td></tr>`).join('');events.innerHTML=s.events.map(e=>`<li>${esc(e)}</li>`).join('');abortJobButton.hidden=!s.abortable;abortJobButton.onclick=async()=>{if(!confirm(`Abort job ${s.jobId}? Running workers may take a moment to stop.`))return;const r=await fetch(`/api/jobs/${encodeURIComponent(s.jobId)}/abort`,{method:'POST'});if(!r.ok)throw new Error(await r.text());await refresh()};artifactSection.hidden=!s.completed;artifacts.innerHTML=(s.artifacts||[]).map(a=>`<li><a href="${esc(a.url)}" download>${esc(a.name)}</a> <span class="muted">(${size(a.size)})</span></li>`).join('')||'<li class="muted">No downloadable artifacts.</li>';purge.hidden=!s.completed;purge.onclick=async()=>{if(!confirm(`Permanently delete job ${s.jobId} and all of its local artifacts?`))return;const r=await fetch(`/api/jobs/${encodeURIComponent(s.jobId)}`,{method:'DELETE'});if(!r.ok)throw new Error(await r.text());location.href='/dashboard'};}catch(e){error.textContent=e;}setTimeout(refresh,1000)}refresh();
			</script></body></html>
			""";
}
