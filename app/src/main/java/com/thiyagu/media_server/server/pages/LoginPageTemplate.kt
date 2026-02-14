package com.thiyagu.media_server.server.pages

import java.io.Writer

object LoginPageTemplate {
    fun Writer.respondLoginPage(themeParam: String) {
        write("""
<!DOCTYPE html>
<html lang="en" class="${if (themeParam == "dark") "dark" else ""}">
<head>
    <meta charset="utf-8"/>
    <meta content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover" name="viewport"/>
    <meta content="${if (themeParam == "dark") "#0a0a0a" else "#F4F4F5"}" name="theme-color"/>
    <title>LANflix - Unlock</title>
    <link href="https://fonts.googleapis.com/css2?family=Spline+Sans:wght@300;400;500;600;700&display=swap" rel="stylesheet"/>
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
    </style>
</head>
<body class="bg-background text-text-main min-h-screen">
    <main class="min-h-screen flex items-center justify-center px-6 py-10">
        <div class="w-full max-w-sm bg-surface/95 border border-white/10 rounded-3xl p-6 shadow-2xl backdrop-blur">
            <div class="flex items-center gap-3 mb-6">
                <div class="w-12 h-12 rounded-2xl bg-primary/20 text-primary flex items-center justify-center text-xl font-bold">LF</div>
                <div>
                    <div class="text-xl font-bold">LANflix</div>
                    <div class="text-xs text-text-sub">Server PIN required</div>
                </div>
            </div>

            <label class="text-xs font-bold text-text-sub">PIN</label>
            <input id="pin-input" type="password" inputmode="numeric" pattern="[0-9]*" maxlength="8"
                class="mt-2 w-full bg-surface-light border border-white/10 rounded-2xl py-3 px-4 text-lg tracking-widest text-center placeholder:text-text-sub/40 focus:ring-1 focus:ring-primary focus:border-primary/50 outline-none"
                placeholder="1234" autocomplete="one-time-code"/>

            <button id="pin-submit" class="mt-4 w-full bg-primary text-black font-bold rounded-2xl py-3 transition-transform active:scale-95">
                Unlock
            </button>

            <p id="pin-error" class="mt-3 text-sm text-red-400 hidden">Incorrect PIN.</p>
            <p class="mt-4 text-xs text-text-sub">
                Set or change the PIN from App Settings on the server device.
            </p>
        </div>
    </main>

    <script>
        (function() {
            const pinInput = document.getElementById('pin-input');
            const submitBtn = document.getElementById('pin-submit');
            const errorEl = document.getElementById('pin-error');

            function getPinKey() { return 'lanflix_pin_' + window.location.host; }
            function getClientKey() { return 'lanflix_client_' + window.location.host; }

            function setPin(pin) {
                if (pin) localStorage.setItem(getPinKey(), pin);
            }

            function clearPin() {
                localStorage.removeItem(getPinKey());
            }

            function setError(message) {
                if (!errorEl) return;
                if (message) {
                    errorEl.textContent = message;
                    errorEl.classList.remove('hidden');
                } else {
                    errorEl.classList.add('hidden');
                }
            }

            function setLoading(isLoading) {
                submitBtn.disabled = isLoading;
                submitBtn.textContent = isLoading ? 'Checking...' : 'Unlock';
                if (isLoading) {
                    submitBtn.classList.add('opacity-70');
                } else {
                    submitBtn.classList.remove('opacity-70');
                }
            }

            function buildRedirect(pin) {
                const params = new URLSearchParams();
                const urlParams = new URLSearchParams(window.location.search);
                const theme = urlParams.get('theme') || localStorage.getItem('lanflix_theme') || 'dark';
                params.set('theme', theme);
                const lastMode = localStorage.getItem('lanflix_last_mode');
                const lastPath = localStorage.getItem('lanflix_last_path');
                if (lastMode === 'tree') {
                    params.set('mode', 'tree');
                    if (lastPath) params.set('path', lastPath);
                }
                params.set('pin', pin);
                const clientId = localStorage.getItem(getClientKey()) || localStorage.getItem('lanflix_client');
                if (clientId) params.set('client', clientId);
                return '/?' + params.toString();
            }

            async function verify() {
                const pin = (pinInput.value || '').trim();
                if (!pin) {
                    setError('PIN required.');
                    pinInput.focus();
                    return;
                }
                setError('');
                setLoading(true);
                try {
                    const res = await fetch('/api/ping?pin=' + encodeURIComponent(pin), { cache: 'no-store' });
                    const data = await res.json().catch(function() { return null; });
                    if (data && data.authorized) {
                        setPin(pin);
                        window.location.href = buildRedirect(pin);
                        return;
                    }
                    clearPin();
                    setError('Incorrect PIN.');
                } catch (e) {
                    setError('Unable to reach server.');
                } finally {
                    setLoading(false);
                }
            }

            submitBtn.addEventListener('click', verify);
            pinInput.addEventListener('keydown', function(e) {
                if (e.key === 'Enter') verify();
            });

            const urlPin = new URLSearchParams(window.location.search).get('pin');
            if (urlPin) {
                pinInput.value = urlPin;
                setTimeout(verify, 50);
            } else {
                pinInput.focus();
            }
        })();
    </script>
</body>
</html>
""")
        flush()
    }
}
