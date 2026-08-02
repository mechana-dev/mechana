package dev.mechana.server;

import dev.mechana.coordinator.InMemoryJobMonitor;
import java.util.List;
import java.util.Set;

/** Read model and dependency-free UI for one running Mechana server. */
final class ServerDashboard {
	record WorkerSnapshot(String id, String address, String state, String activity, String jobId,
			Set<String> capabilities, String lastSeen) {
	}

	record Snapshot(long serverPid, String serverDate, String serverTime, String serverUptime, int connectedWorkers,
			int registeredWorkers, int activeJobs, int completedJobs, List<WorkerSnapshot> workers,
			List<InMemoryJobMonitor.Snapshot> activeJobItems, List<InMemoryJobMonitor.Snapshot> completedJobItems) {
	}

	private ServerDashboard() {
	}

	static String html(String statusUrl) {
		return HTML.replace("__STATUS_URL__", statusUrl);
	}

	private static final String HTML = """
			<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
			<title>Mechana Server</title><style>
			:root{color-scheme:dark;font-family:ui-sans-serif,system-ui;background:#0b1020;color:#e7ecf5}body{max-width:1200px;margin:0 auto;padding:28px}h1{margin:0 0 4px}.muted{color:#91a0b8}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;margin:22px 0}.card,section{background:#141b2d;border:1px solid #26324a;border-radius:12px;padding:16px;margin-bottom:16px}.value{font-size:28px;font-weight:700}table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:10px;border-bottom:1px solid #26324a}th{color:#91a0b8}a{color:#75a7ff;text-decoration:none}a:hover{text-decoration:underline}.CONNECTED,.RUNNING,.WORKING{color:#58a6ff}.SUCCEEDED,.IDLE{color:#3ddc97}.FAILED,.CANCELLED,.DISCONNECTED,.OFFLINE{color:#ff6b6b}code{word-break:break-all}.empty{padding:20px 0;color:#91a0b8}button{background:#7c2d3a;color:#fff;border:1px solid #b94b5d;border-radius:7px;padding:7px 11px;cursor:pointer}button:hover{background:#99384a}
			</style></head><body><h1>Mechana Server</h1><div class="muted">Live workers and job history</div>
			<div class="cards"><div class="card"><div class="muted">Server PID</div><div class="value" id="serverPid">—</div></div><div class="card"><div class="muted">Server date</div><div class="value" id="serverDate">—</div></div><div class="card"><div class="muted">Server time</div><div class="value" id="serverTime">—</div></div><div class="card"><div class="muted">Server uptime</div><div class="value" id="serverUptime">—</div></div><div class="card"><div class="muted">Connected workers</div><div class="value" id="connected">0</div></div><div class="card"><div class="muted">Registered workers</div><div class="value" id="registered">0</div></div><div class="card"><div class="muted">Active jobs</div><div class="value" id="active">0</div></div><div class="card"><div class="muted">Completed jobs</div><div class="value" id="completed">0</div></div></div>
			<section><h2>Workers</h2><table><thead><tr><th>Worker</th><th>IP address</th><th>State</th><th>Activity</th><th>Job</th><th>Capabilities</th><th>Last seen</th></tr></thead><tbody id="workers"></tbody></table><div class="empty" id="noWorkers">No workers have registered.</div></section>
			<section><h2>Active jobs</h2><table><thead><tr><th>Job</th><th>Plugin</th><th>Stage</th><th>Progress</th><th>Work units</th><th>Elapsed</th><th>Actions</th></tr></thead><tbody id="activeJobRows"></tbody></table><div class="empty" id="noActiveJobs">No active jobs.</div></section>
			<section><h2>Completed jobs</h2><table><thead><tr><th>Job</th><th>Plugin</th><th>Stage</th><th>Progress</th><th>Work units</th><th>Elapsed</th><th>Storage</th></tr></thead><tbody id="completedJobRows"></tbody></table><div class="empty" id="noCompletedJobs">No completed jobs.</div></section><p class="FAILED" id="error"></p><script>
			const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
			const row=j=>`<tr><td><a href="/dashboard/jobs/${encodeURIComponent(j.jobId)}"><code>${esc(j.jobId)}</code></a></td><td>${esc(j.plugin)}</td><td class="${esc(j.stage)}">${esc(j.stage)}</td><td>${j.progress}%</td><td>${j.completedWorkUnits} / ${j.totalWorkUnits}</td><td>${esc(j.elapsed)}</td>`;
			async function abortJob(id){if(!confirm(`Abort job ${id}? Running workers may take a moment to stop.`))return;const r=await fetch(`/api/jobs/${encodeURIComponent(id)}/abort`,{method:'POST'});if(!r.ok)throw new Error(await r.text());await refresh(false)}
			async function purgeJob(id){if(!confirm(`Permanently delete job ${id} and all of its local artifacts?`))return;const r=await fetch(`/api/jobs/${encodeURIComponent(id)}`,{method:'DELETE'});if(!r.ok)throw new Error(await r.text());await refresh(false)}
			async function refresh(schedule=true){try{const s=await fetch('__STATUS_URL__',{cache:'no-store'}).then(r=>r.json());serverPid.textContent=s.serverPid;serverDate.textContent=s.serverDate;serverTime.textContent=s.serverTime;serverUptime.textContent=s.serverUptime;connected.textContent=s.connectedWorkers;registered.textContent=s.registeredWorkers;active.textContent=s.activeJobs;completed.textContent=s.completedJobs;noWorkers.hidden=s.workers.length>0;noActiveJobs.hidden=s.activeJobItems.length>0;noCompletedJobs.hidden=s.completedJobItems.length>0;workers.innerHTML=s.workers.map(w=>`<tr><td><code>${esc(w.id)}</code></td><td><code>${esc(w.address)}</code></td><td class="${esc(w.state)}">${esc(w.state)}</td><td class="${esc(w.activity)}">${esc(w.activity)}</td><td>${w.jobId?`<a href="/dashboard/jobs/${encodeURIComponent(w.jobId)}"><code>${esc(w.jobId)}</code></a>`:'—'}</td><td>${w.capabilities.map(esc).join(', ')||'—'}</td><td>${esc(w.lastSeen)}</td></tr>`).join('');activeJobRows.innerHTML=s.activeJobItems.map(j=>row(j)+`<td><button onclick="abortJob('${esc(j.jobId)}')">Abort</button></td></tr>`).join('');completedJobRows.innerHTML=s.completedJobItems.map(j=>row(j)+`<td><button onclick="purgeJob('${esc(j.jobId)}')">Purge</button></td></tr>`).join('');error.textContent='';}catch(e){error.textContent=e;}if(schedule)setTimeout(refresh,1000)}refresh();
			</script></body></html>
			""";
}
