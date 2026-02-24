package com.thiyagu.media_server.server.pages

import java.io.Writer

object DiagnosticsPageTemplate {
    fun Writer.respondDiagnosticsPage(themeParam: String) {
        write("""
<!DOCTYPE html>
<html lang="en" class="${if (themeParam == "dark") "dark" else ""}">
<head>
    <meta charset="utf-8"/>
    <meta content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover" name="viewport"/>
    <meta content="${if (themeParam == "dark") "#0a0a0a" else "#F4F4F5"}" name="theme-color"/>
    <title>LANflix Diagnostics</title>
    <link href="https://fonts.googleapis.com/css2?family=Spline+Sans:wght@300;400;500;600;700&display=swap" rel="stylesheet"/>
    <link href="https://fonts.googleapis.com/css2?family=Material+Symbols+Rounded:opsz,wght,FILL,GRAD@24,400,0,0" rel="stylesheet"/>
    <script src="https://cdn.tailwindcss.com?plugins=forms,container-queries"></script>
    <script>
        tailwind.config = {
            darkMode: 'class',
            theme: {
                extend: {
                    colors: ${if (themeParam == "dark") """
                        {
                            primary: "#FAC638",
                            background: "#0a0a0a",
                            surface: "#171717",
                            "surface-light": "#262626",
                            "text-main": "#f2f2f2",
                            "text-sub": "#9ca3af"
                        }
                    """ else """
                        {
                            primary: "#D4A526",
                            background: "#F4F4F5",
                            surface: "#FFFFFF",
                            "surface-light": "#F9FAFB",
                            "text-main": "#09090B",
                            "text-sub": "#71717A"
                        }
                    """},
                    fontFamily: {
                        display: ['Spline Sans', 'sans-serif']
                    }
                }
            }
        };
    </script>
    <style>
        :root {
            --color-primary: ${if (themeParam == "dark") "#FAC638" else "#D4A526"};
        }
        body {
            font-family: 'Spline Sans', sans-serif;
            -webkit-tap-highlight-color: transparent;
            -webkit-font-smoothing: antialiased;
        }
        #video-selected {
            overflow-wrap: anywhere;
        }
        #video-select {
            min-width: 0;
            max-width: 100%;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
    </style>
    <script>
        const LanflixAuth = {
            getPinKey() { return `lanflix_pin_${'$'}{window.location.host}`; },
            getPin() { return localStorage.getItem(this.getPinKey()) || localStorage.getItem('lanflix_pin'); },
            setPin(pin) { if (pin) localStorage.setItem(this.getPinKey(), pin); },
            clearPin() { localStorage.removeItem(this.getPinKey()); }
        };
        const LanflixClient = {
            getClientKey() { return `lanflix_client_${'$'}{window.location.host}`; },
            getId() { return localStorage.getItem(this.getClientKey()) || localStorage.getItem('lanflix_client'); },
            setId(id) { if (id) localStorage.setItem(this.getClientKey(), id); },
            clearId() { localStorage.removeItem(this.getClientKey()); }
        };

        (function persistAuthFromUrl() {
            const params = new URLSearchParams(window.location.search);
            const urlPin = params.get('pin');
            const urlClient = params.get('client');
            if (urlPin) LanflixAuth.setPin(urlPin);
            if (urlClient) LanflixClient.setId(urlClient);
        })();

        function withAuthParam(url) {
            const pin = LanflixAuth.getPin();
            const clientId = LanflixClient.getId();
            const resolved = new URL(url, window.location.origin);
            if (pin && !resolved.searchParams.has('pin')) resolved.searchParams.set('pin', pin);
            if (clientId && !resolved.searchParams.has('client')) resolved.searchParams.set('client', clientId);
            const query = resolved.searchParams.toString();
            return query ? `${'$'}{resolved.pathname}?${'$'}{query}` : resolved.pathname;
        }

        async function apiFetch(input, init = {}) {
            const pin = LanflixAuth.getPin();
            const clientId = LanflixClient.getId();
            const resolved = new URL(input, window.location.origin);
            if (pin && !resolved.searchParams.has('pin')) resolved.searchParams.set('pin', pin);
            if (clientId && !resolved.searchParams.has('client')) resolved.searchParams.set('client', clientId);
            const headers = new Headers(init.headers || {});
            if (pin) headers.set('X-Lanflix-Pin', pin);
            if (clientId) headers.set('X-Lanflix-Client', clientId);
            return fetch(resolved.toString(), { cache: 'no-store', ...init, headers });
        }

        function toggleTheme() {
            const current = document.documentElement.classList.contains('dark') ? 'dark' : 'light';
            const next = current === 'dark' ? 'light' : 'dark';
            localStorage.setItem('lanflix_theme', next);
            const url = new URL(window.location);
            url.searchParams.set('theme', next);
            window.location.href = url.toString();
        }
    </script>
</head>
<body class="bg-background text-text-main min-h-screen pb-20">
    <header class="sticky top-0 z-40 bg-background/80 backdrop-blur-xl border-b border-white/5">
        <div class="flex items-center justify-between px-6 py-4 max-w-5xl mx-auto">
            <div class="flex items-center gap-3">
                <a href="/?theme=$themeParam" class="w-10 h-10 rounded-full bg-surface-light flex items-center justify-center active:scale-90 transition-transform">
                    <span class="material-symbols-rounded">arrow_back</span>
                </a>
                <div class="text-lg font-bold">Diagnostics</div>
            </div>
            <button onclick="toggleTheme()" class="w-10 h-10 rounded-full bg-surface-light/50 flex items-center justify-center active:scale-90 transition-transform">
                <span class="material-symbols-rounded text-xl">${if (themeParam == "dark") "light_mode" else "dark_mode"}</span>
            </button>
        </div>
    </header>

    <main class="px-6 py-6 max-w-5xl mx-auto space-y-6">
        <section class="bg-surface/90 border border-white/10 rounded-2xl p-4">
            <div class="text-xs font-bold text-text-sub">Test Video</div>
            <div id="video-selected" class="mt-2 text-sm">Loading...</div>
            <div class="mt-4 flex gap-3">
                <button id="btn-refresh" class="flex-1 bg-surface-light py-2.5 rounded-xl text-sm font-medium">Refresh List</button>
                <select id="video-select" class="flex-1 bg-surface-light py-2.5 rounded-xl text-sm"></select>
            </div>
        </section>

        <section class="bg-surface/90 border border-white/10 rounded-2xl p-4">
            <div class="flex items-center justify-between">
                <div>
                    <div class="text-sm font-bold">Seek Stress Test</div>
                    <div id="seek-result" class="text-xs text-text-sub mt-1">Run to measure seek latency</div>
                </div>
                <button id="btn-seek" class="bg-surface-light px-4 py-2 rounded-xl text-sm">Run</button>
            </div>
        </section>

        <section class="bg-surface/90 border border-white/10 rounded-2xl p-4">
            <div class="flex items-center justify-between">
                <div>
                    <div class="text-sm font-bold">Throughput Test</div>
                    <div id="throughput-result" class="text-xs text-text-sub mt-1">Target: 11 Mbps</div>
                </div>
                <button id="btn-throughput" class="bg-surface-light px-4 py-2 rounded-xl text-sm">Run</button>
            </div>
        </section>

        <section class="bg-surface/90 border border-white/10 rounded-2xl p-4">
            <div class="flex items-center justify-between">
                <div>
                    <div class="text-sm font-bold">File Exists Test</div>
                    <div id="exists-result" class="text-xs text-text-sub mt-1">Move a file and rerun to validate refresh speed</div>
                </div>
                <div class="flex gap-2">
                    <button id="btn-exists" class="bg-surface-light px-4 py-2 rounded-xl text-sm">Check</button>
                    <button id="btn-refresh-cache" class="bg-primary text-black px-4 py-2 rounded-xl text-sm font-semibold">Refresh Cache</button>
                </div>
            </div>
        </section>
    </main>

    <script>
        const TARGET_MBPS = 11;
        const SEEK_SAMPLE_BYTES = 512 * 1024;
        const MAX_THROUGHPUT_BYTES = 16 * 1024 * 1024;
        const MIN_THROUGHPUT_BYTES = 4 * 1024 * 1024;

        let videos = [];
        let selected = null;

        function updateSelected() {
            const label = selected ? `${'$'}{selected.name} (${'$'}{formatSize(selected.size)})` : 'No video selected';
            document.getElementById('video-selected').textContent = label;
        }

        function formatSize(bytes) {
            if (!bytes) return 'Unknown';
            const kb = bytes / 1024;
            const mb = kb / 1024;
            const gb = mb / 1024;
            if (gb >= 1) return `${'$'}{gb.toFixed(1)} GB`;
            if (mb >= 1) return `${'$'}{mb.toFixed(1)} MB`;
            return `${'$'}{kb.toFixed(1)} KB`;
        }

        async function loadVideos() {
            document.getElementById('video-selected').textContent = 'Loading...';
            const res = await apiFetch('/api/cache');
            const data = await res.json();
            videos = data.videos || [];
            const select = document.getElementById('video-select');
            select.innerHTML = '';
            videos.slice(0, 200).forEach((video, idx) => {
                const opt = document.createElement('option');
                opt.value = idx.toString();
                opt.textContent = video.name;
                select.appendChild(opt);
            });
            selected = videos[0] || null;
            updateSelected();
        }

        function buildVideoUrl(video) {
            const base = `/${'$'}{encodeURIComponent(video.name)}?path=${'$'}{encodeURIComponent(video.path)}`;
            return withAuthParam(base);
        }

        function buildExistsUrl(video) {
            const base = `/api/exists/${'$'}{encodeURIComponent(video.name)}?path=${'$'}{encodeURIComponent(video.path)}`;
            return withAuthParam(base);
        }

        async function measureTtfb(url, range) {
            const start = performance.now();
            const res = await fetch(url, { headers: { Range: `bytes=${'$'}{range.start}-${'$'}{range.end}` }, cache: 'no-store' });
            if (!res.ok && res.status !== 206) throw new Error(`Bad status ${'$'}{res.status}`);
            const reader = res.body.getReader();
            await reader.read();
            try { reader.cancel(); } catch (e) {}
            return Math.round(performance.now() - start);
        }

        async function runSeekTest() {
            if (!selected) return;
            const target = document.getElementById('seek-result');
            target.textContent = 'Running seek test...';
            const size = selected.size || MIN_THROUGHPUT_BYTES;
            const offsets = [0.05, 0.5, 0.9].map(p => Math.round(size * p));
            const ranges = offsets.map(start => ({
                start,
                end: Math.min(size - 1, start + SEEK_SAMPLE_BYTES - 1)
            }));
            const url = buildVideoUrl(selected);
            const results = [];
            for (const range of ranges) {
                results.push(await measureTtfb(url, range));
            }
            results.sort((a, b) => a - b);
            const median = results[Math.floor(results.length / 2)];
            target.textContent = `Median: ${'$'}{median}ms (Samples: ${'$'}{results.join(' • ')}ms)`;
        }

        async function runThroughputTest() {
            if (!selected) return;
            const target = document.getElementById('throughput-result');
            target.textContent = 'Running throughput test...';
            const size = selected.size || MIN_THROUGHPUT_BYTES;
            const rangeSize = Math.min(MAX_THROUGHPUT_BYTES, size);
            const url = buildVideoUrl(selected);
            const start = performance.now();
            const res = await fetch(url, { headers: { Range: `bytes=0-${'$'}{rangeSize - 1}` }, cache: 'no-store' });
            const reader = res.body.getReader();
            let total = 0;
            while (true) {
                const { value, done } = await reader.read();
                if (done) break;
                total += value.length;
            }
            const duration = (performance.now() - start) / 1000;
            const mbps = (total * 8) / duration / 1_000_000;
            const status = mbps >= TARGET_MBPS ? 'PASS' : 'LOW';
            target.textContent = `${'$'}{mbps.toFixed(1)} Mbps (${'$'}{status}, target ${'$'}{TARGET_MBPS} Mbps)`;
        }

        async function runExistsTest() {
            if (!selected) return;
            const target = document.getElementById('exists-result');
            target.textContent = 'Checking file existence...';
            const url = buildExistsUrl(selected);
            const start = performance.now();
            const res = await apiFetch(url);
            const body = await res.json();
            const elapsed = Math.round(performance.now() - start);
            const status = body.exists ? 'Found' : 'Missing';
            target.textContent = `${'$'}{status} (${'$'}{elapsed}ms). Move the file and rerun to validate refresh.`;
        }

        async function refreshCache() {
            const target = document.getElementById('exists-result');
            target.textContent = 'Refreshing server cache...';
            await apiFetch('/api/refresh-cache');
            target.textContent = 'Cache refresh triggered. Rerun exists test.';
            await loadVideos();
        }

        document.getElementById('btn-refresh').addEventListener('click', loadVideos);
        document.getElementById('video-select').addEventListener('change', (e) => {
            const idx = parseInt(e.target.value, 10);
            selected = videos[idx] || null;
            updateSelected();
        });
        document.getElementById('btn-seek').addEventListener('click', runSeekTest);
        document.getElementById('btn-throughput').addEventListener('click', runThroughputTest);
        document.getElementById('btn-exists').addEventListener('click', runExistsTest);
        document.getElementById('btn-refresh-cache').addEventListener('click', refreshCache);

        loadVideos();
    </script>
</body>
</html>
""".trimIndent())
        flush()
    }
}
