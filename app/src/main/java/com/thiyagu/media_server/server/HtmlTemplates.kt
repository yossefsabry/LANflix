package com.thiyagu.media_server.server

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.thiyagu.media_server.utils.VideoVisibilityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Writer
import java.io.File
import java.net.URLEncoder

object HtmlTemplates {
    suspend fun Writer.respondHtmlPage(
        mode: String?,
        pathParam: String,
        themeParam: String,
        visibilityManager: VideoVisibilityManager,
        allVideoFiles: List<DocumentFile>
    ) {
        
        // --- HTML HEAD ---
        write("""
<!DOCTYPE html>
<html lang="en" class="${if (themeParam == "dark") "dark" else ""}">
<head>
    <meta charset="utf-8"/>
    <meta content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover" name="viewport"/>
    <meta content="${if (themeParam == "dark") "#0a0a0a" else "#F4F4F5"}" name="theme-color"/>
    <title>LANflix</title>
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
        .hide-scrollbar::-webkit-scrollbar { display: none; }
        .hide-scrollbar { -ms-overflow-style: none; scrollbar-width: none; }
        .progress-bar-container {
             position: fixed;
             top: 0;
             left: 0;
             right: 0;
             height: 5px;
             z-index: 100;
             pointer-events: none;
             transition: transform 0.3s ease;
             transform: translateY(-100%);
             background: rgba(255,255,255,0.1);
             overflow: hidden;
        }
        .progress-bar-container.visible {
            transform: translateY(0);
        }
        .progress-bar-fill {
            height: 100%;
            background-color: var(--color-primary, #D4A526); /* Fallback */
            width: 30%;
            animation: indeterminate 2s infinite linear;
        }
        @keyframes indeterminate {
            0% { transform: translateX(-100%); width: 30%; }
            50% { width: 60%; }
            100% { transform: translateX(200%); width: 30%; }
        }
        @keyframes wobble {
            0% { transform: translateX(0%); }
            15% { transform: translateX(-25%) rotate(-5deg); }
            30% { transform: translateX(20%) rotate(3deg); }
            45% { transform: translateX(-15%) rotate(-3deg); }
            60% { transform: translateX(10%) rotate(2deg); }
            75% { transform: translateX(-5%) rotate(-1deg); }
            100% { transform: translateX(0%); }
        }
        
        /* Skeleton Loading */
        .skeleton {
            background: linear-gradient(90deg, #ffffff05 25%, #ffffff10 50%, #ffffff05 75%);
            background-size: 200% 100%;
            animation: shimmer 1.5s infinite;
            border-radius: 12px;
        }
        @keyframes shimmer {
            0% { background-position: 200% 0; }
            100% { background-position: -200% 0; }
        }

        /* Responsive Grid */
        .auto-grid {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
            gap: 1rem;
        }

        /* View Transitions */
        ::view-transition-old(root),
        ::view-transition-new(root) {
            animation-duration: 0.3s;
        }
    </style>
    <script>
        // Theme & Persistence Logic
        (function() {
            // Check LocalStorage for theme preference
            const savedTheme = localStorage.getItem('lanflix_theme');
            const urlParams = new URLSearchParams(window.location.search);
            const urlTheme = urlParams.get('theme');
            const mode = urlParams.get('mode');
            const path = urlParams.get('path');
            
            // If URL has no theme but we have a saved one, redirect to apply it (Server-Side Rendering needs it)
            // Or better: Apply it via JS immediately to body class, but SSR color variables might be wrong.
            // Since we rely on server-injected colors, we MUST reload/redirect if the server rendered the wrong theme.
            // But to avoid loops, only redirect if they differ.
            
            let targetTheme = urlTheme || savedTheme || 'dark'; // Default to dark
            
            // Save initial state
            if (urlTheme) localStorage.setItem('lanflix_theme', urlTheme);
            if (mode) localStorage.setItem('lanflix_last_mode', mode);
            if (mode === 'tree' && path !== null) localStorage.setItem('lanflix_last_path', path);
            
            // Redirect if needed
            if (!urlTheme && savedTheme) {
                 // Build new URL
                 urlParams.set('theme', savedTheme);
                 window.location.replace('?' + urlParams.toString());
            }

            // Restore State for Root Visit
            if (!window.location.search) {
                const lastMode = localStorage.getItem('lanflix_last_mode');
                if (lastMode === 'tree') {
                   const lastPath = localStorage.getItem('lanflix_last_path');
                   window.location.replace('/?mode=tree&theme=' + targetTheme + (lastPath ? '&path=' + encodeURIComponent(lastPath) : ''));
                } else {
                   window.location.replace('/?theme=' + targetTheme);
                }
            }
        })();

        function toggleTheme() {
            const current = document.documentElement.classList.contains('dark') ? 'dark' : 'light';
            const next = current === 'dark' ? 'light' : 'dark';
            localStorage.setItem('lanflix_theme', next);
            
            // Reload with new theme param
            const url = new URL(window.location);
            url.searchParams.set('theme', next);
            window.location.href = url.toString();
        }
    </script>
    <script>
        if ('serviceWorker' in navigator) {
            window.addEventListener('load', () => {
                navigator.serviceWorker.register('/sw.js').catch(err => console.log('SW Registration failed:', err));
            });
        }
    </script>
    <style>
        /* Disconnect Overlay */
        #connection-lost {
            position: fixed;
            inset: 0;
            z-index: 200;
            background: rgba(0,0,0,0.8);
            backdrop-filter: blur(8px);
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            opacity: 0;
            pointer-events: none;
            transition: opacity 0.3s ease;
        }
        #connection-lost.visible {
            opacity: 1;
            pointer-events: auto;
        }
    </style>
    <script>
        // Lazy load thumbnails
        if ('IntersectionObserver' in window) {
            window.imageObserver = new IntersectionObserver((entries, observer) => {
                entries.forEach(entry => {
                    if (entry.isIntersecting) {
                        const img = entry.target;
                        img.src = img.dataset.src;
                        img.classList.remove('opacity-0');
                        observer.unobserve(img);
                    }
                });
            }, { rootMargin: '50px 0px', threshold: 0.01 });

            document.addEventListener('DOMContentLoaded', () => {
                document.querySelectorAll('img[loading="lazy"]').forEach(img => window.imageObserver.observe(img));
            });
        }
    </script>
</head>
<body class="bg-background text-text-main min-h-screen pb-24 selection:bg-primary selection:text-black">
    
    <!-- Connection Lost Overlay -->
    <div id="connection-lost">
        <span class="material-symbols-rounded text-6xl text-red-500 mb-4 animate-bounce">wifi_off</span>
        <h2 class="text-2xl font-bold mb-2">Connection Lost</h2>
        <p class="text-text-main/60 mb-6 text-center max-w-xs">Checking signal...</p>
        <button onclick="window.location.reload()" class="bg-surface-light px-6 py-2 rounded-full font-medium active:scale-95 transition-transform">Retry Now</button>
    </div>
    
    <!-- Progress Bar (Scanning) -->
    <div id="scan-progress-container" class="fixed top-[72px] left-0 right-0 z-40 px-6 transition-all duration-300 opacity-0 pointer-events-none -translate-y-4">
         <div class="max-w-md mx-auto bg-surface/90 backdrop-blur border border-primary/20 rounded-full p-1 flex items-center gap-3 shadow-lg">
             <div class="w-5 h-5 rounded-full border-2 border-primary border-t-transparent animate-spin ml-2"></div>
             <div class="flex-1 text-xs font-medium text-text-main">
                 Scanning Library... <span id="scan-count" class="text-primary font-bold ml-1">0</span>
             </div>
         </div>
    </div>
    
    <!-- Global Loading Overlay (for navigation) -->
    <div id="nav-loading-overlay" class="fixed inset-0 z-50 bg-background/80 backdrop-blur-sm flex items-center justify-center opacity-0 pointer-events-none transition-opacity duration-200">
        <div class="bg-surface rounded-2xl p-8 shadow-2xl border border-white/10 flex flex-col items-center gap-4">
            <div class="w-16 h-16 rounded-full border-4 border-primary border-t-transparent animate-spin"></div>
            <p class="text-text-main font-medium">Loading...</p>
        </div>
    </div>

    <!-- Header -->
    <header class="fixed top-0 left-0 right-0 z-40 bg-background/80 backdrop-blur-xl border-b border-white/5 transition-all duration-300" id="header">
        <div class="flex items-center justify-between px-6 py-4 max-w-7xl mx-auto">
             <div class="flex items-center gap-3">
                 <div class="flex items-center gap-3" id="header-nav-container">
                     <!-- Populated by JS or SSR -->
                     ${if (pathParam.isNotEmpty() == true) """
                     <a href="${if (pathParam.contains("/")) "/?mode=tree&path=" + URLEncoder.encode(pathParam.substringBeforeLast("/"), "UTF-8") else "/?mode=tree"}" 
                        class="w-10 h-10 rounded-full bg-surface-light flex items-center justify-center active:scale-90 transition-transform">
                         <span class="material-symbols-rounded">arrow_back</span>
                     </a>
                     """ else """
                     <a href="/exit" class="w-10 h-10 rounded-full bg-surface-light flex items-center justify-center active:scale-90 transition-transform">
                         <span class="material-symbols-rounded text-red-400">power_settings_new</span>
                     </a>
                     """}
                 </div>
                 
                 <div class="flex items-center gap-2">
                     <span class="text-xl font-bold tracking-tight" id="header-title">LANflix</span>
                 </div>
             </div>
             
             <div class="flex items-center gap-3">
                <button onclick="toggleTheme()" class="w-10 h-10 rounded-full bg-surface-light/50 flex items-center justify-center active:scale-90 transition-transform">
                    <span class="material-symbols-rounded text-xl">${if (themeParam == "dark") "light_mode" else "dark_mode"}</span>
                </button>
                 <a href="/profile" class="w-10 h-10 rounded-full bg-surface-light flex items-center justify-center ring-2 ring-primary/20 active:scale-95 transition-transform relative">
                     <span class="material-symbols-rounded text-text-sub">person</span>
                     <div class="absolute top-1 right-1 w-2.5 h-2.5 bg-green-500 rounded-full border-2 border-background"></div>
                 </a>
             </div>
        </div>
    </header>

    <main class="pt-24 pb-10 px-4 max-w-7xl mx-auto min-h-[80vh]">
""")
                                
        // --- CONTENT GENERATION ---
        withContext(Dispatchers.IO) {
            val isTree = (mode == "tree")
            
            // Tree Section Wrapper
            write("""<div id="tree-section" class="${if(isTree) "" else "hidden"}">""")

            if (isTree) {
                // --- TREE BROWSER MODE ---
                // Server-side rendering removed for performance (Progressive Loading)
                
                val breadcrumbs = mutableListOf<Pair<String, String>>()
                breadcrumbs.add("Home" to "")
                
                if (pathParam.isNotEmpty()) {
                    val parts = pathParam.split("/").filter { it.isNotEmpty() }
                    var currentPath = ""
                    for (part in parts) {
                        currentPath += "/$part"
                        breadcrumbs.add(part to currentPath)
                    }
                }

                // Breadcrumbs
                write("""<div id="breadcrumbs" class="flex items-center gap-2 mb-4 overflow-x-auto whitespace-nowrap px-1">""")
                breadcrumbs.forEachIndexed { index, (name, path) ->
                    if (index > 0) write("""<span class="text-text-sub">/</span>""")
                    val isLast = index == breadcrumbs.size - 1
                    val color = if (isLast) "text-primary font-bold" else "text-text-sub hover:text-text-main"
                    write("""<a href="/?mode=tree&path=$path" class="$color text-sm">$name</a>""")
                }
                write("""</div>""")

                // Grid (Empty initially, populated by Client JS)
                write("""<div id="tree-grid" class="grid grid-cols-2 gap-4"></div>""")
                
                // Loading Indicators & Sentinel for Infinite Scroll
                write("""
                    <div id="loading-indicator" class="hidden col-span-full flex justify-center py-8">
                        <div class="flex items-center gap-3 text-text-sub">
                            <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-primary"></div>
                        </div>
                    </div>
                    <div id="loading-sentinel"></div>
                """)

            } else {
                 // Empty Tree Structure for SPA
                 write("""
                    <div id="breadcrumbs" class="flex items-center gap-2 mb-4 overflow-x-auto whitespace-nowrap px-1"></div>
                    <div id="tree-grid" class="grid grid-cols-2 gap-4"></div>
                 """)
            }
            write("</div>") // Close Tree Section

            // Video Section Wrapper
            write("""<div id="video-section" class="${if (!isTree) "" else "hidden"}">""")
            
            if (!isTree) {
                // --- DEFAULT FLAT MODE (Live/Progressive) ---
                
                // No directory check needed here (scanning or cached)
                // Simply show what we have so far
                
                val currentFiles = allVideoFiles.toList() // synchronized in caller if needed, but here we take what we have
                val filteredFiles = currentFiles.filter { 
                    !visibilityManager.isVideoHidden(it.uri.toString())
                }

                val recentFiles = filteredFiles.sortedByDescending { it.lastModified() }.take(5)

                // Recently Added
                write("""
                <div class="relative mb-6">
                    <span class="material-symbols-rounded absolute left-4 top-1/2 -translate-y-1/2 text-text-sub">search</span>
                    <input type="text" placeholder="Search local videos..." 
                        class="w-full bg-surface-light border border-white/5 rounded-full py-3.5 pl-12 pr-12 text-sm placeholder:text-text-sub/50 focus:ring-1 focus:ring-primary focus:border-primary/50 transition-all outline-none text-text-main">
                    <button class="absolute right-2 top-1/2 -translate-y-1/2 p-2 rounded-full hover:bg-white/5 text-text-sub">
                        <span class="material-symbols-rounded text-[20px]">tune</span>
                    </button>
                </div>
                <section>
                     <div class="flex items-center justify-between mb-4">
                          <h2 class="text-lg font-bold">Recently Added</h2>
                          <button onclick="document.getElementById('all-videos').scrollIntoView({behavior: 'smooth'})" class="text-xs font-bold text-primary">See All</button>
                     </div>
                     <div id="recently-added-grid" class="flex gap-4 overflow-x-auto hide-scrollbar -mx-4 px-4 scroll-pl-4 snap-x">""")
                
                for (file in recentFiles) {
                    val name = file.name ?: "Unknown"
                    val fileSize = file.length() / (1024 * 1024)
                    write("""
                        <a href="/${name}" class="flex-none w-64 snap-start group relative rounded-2xl overflow-hidden aspect-video bg-surface-light border border-white/5">
                            <img data-src="/api/thumbnail/${name}" src="data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAxIDEiPjwvc3ZnPg==" loading="lazy" class="absolute inset-0 w-full h-full object-cover opacity-0 transition-opacity duration-500"/>
                            <div class="absolute inset-0 bg-black/20 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity z-10">
                                <div class="w-12 h-12 bg-white/20 backdrop-blur-sm rounded-full flex items-center justify-center"><span class="material-symbols-rounded text-white text-3xl">play_arrow</span></div>
                            </div>
                            <div class="absolute inset-0 flex items-center justify-center bg-white/5 -z-10"><span class="material-symbols-rounded text-4xl text-text-sub/20">movie</span></div>
                            <div class="absolute inset-0 bg-gradient-to-t from-black/90 via-black/20 to-transparent"></div>
                            <div class="absolute bottom-3 left-3 right-3">
                                <h3 class="font-bold text-sm text-white line-clamp-1 mb-1">${name}</h3>
                                <div class="flex items-center gap-2 text-[10px] text-gray-300 font-medium">
                                    <span class="bg-primary/20 text-primary px-1.5 py-0.5 rounded">NEW</span>
                                    <span>${fileSize} MB</span>
                                </div>
                            </div>
                        </a>""")
                }
                if (recentFiles.isEmpty()) write("""<div class="w-full text-center py-8 text-text-sub text-sm">No recent videos</div>""")
                write("""</div></section>""")
                
                // All Videos - Progressive Loading
                write("""
                <section class="mt-6">
                    <div class="flex items-center justify-between mb-4">
                         <h2 id="all-videos" class="text-lg font-bold">All Videos</h2>
                         <span id="video-count" class="text-xs text-text-sub/60 font-medium">Loading...</span>
                    </div>
                    <div id="video-grid" class="auto-grid pb-8">
                        <!-- Skeleton loaders for immediate feedback -->
                        <div class="skeleton-card animate-pulse bg-surface-light rounded-2xl p-2 border border-white/5">
                            <div class="aspect-[4/3] rounded-xl bg-background mb-3"></div>
                           <div class="px-1 pb-1 space-y-2">
                                <div class="h-4 bg-background rounded w-3/4"></div>
                                <div class="h-3 bg-background rounded w-1/2"></div>
                            </div>
                        </div>
                        <div class="skeleton-card animate-pulse bg-surface-light rounded-2xl p-2 border border-white/5">
                            <div class="aspect-[4/3] rounded-xl bg-background mb-3"></div>
                            <div class="px-1 pb-1 space-y-2">
                                <div class="h-4 bg-background rounded w-3/4"></div>
                                <div class="h-3 bg-background rounded w-1/2"></div>
                            </div>
                        </div>
                    </div>
                    <!-- Loading indicator -->
                    <div id="loading-indicator" class="hidden col-span-full flex justify-center py-8">
                        <div class="flex items-center gap-3 text-text-sub">
                            <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-primary"></div>
                        </div>
                    </div>
                    <!-- Sentinel for infinite scroll -->
                    <div id="loading-sentinel"></div>
                </section>""")
            } else {
                // Empty Video Structure for SPA
                write("""<div id="video-grid" class="auto-grid"></div>""")
            }
            write("</div>") // Close Video Section

            write("""
                <script>
                    /**
                     * LANflix SPA Architecture
                     * Handles Client-Side Routing, Caching, and Progressive Loading.
                     */
                    const App = {
                        state: {
                            mode: 'flat', 
                            path: '',
                            page: 1,
                            items: [],
                            allVideos: [], // Full Cached List
                            isLoading: false,
                            hasMore: true,
                            virtualScroll: false
                        },

                        // IndexedDB Cache Wrapper
                        cache: {
                            db: null,
                            async init() {
                                return new Promise((resolve, reject) => {
                                    const req = indexedDB.open('LANflixDB', 2);
                                    req.onupgradeneeded = e => {
                                        const db = e.target.result;
                                        if (!db.objectStoreNames.contains('items')) db.createObjectStore('items', { keyPath: 'name' });
                                        if (!db.objectStoreNames.contains('tree_items')) db.createObjectStore('tree_items', { keyPath: 'path' });
                                    };
                                    req.onsuccess = () => { this.db = req.result; resolve(); };
                                    req.onerror = reject;
                                });
                            },
                            async get(key, store = 'items') {
                                if (!this.db) await this.init();
                                return new Promise(resolve => {
                                    const tx = this.db.transaction(store, 'readonly');
                                    const req = store === 'items' ? tx.objectStore(store).getAll() : tx.objectStore(store).get(key);
                                    req.onsuccess = () => resolve(req.result);
                                    req.onerror = () => resolve(null);
                                });
                            },
                            async set(key, data, store = 'items') {
                                if (!this.db) await this.init();
                                const tx = this.db.transaction(store, 'readwrite');
                                const os = tx.objectStore(store);
                                if (store === 'items') {
                                    data.forEach(item => { try { os.put(item); } catch(e){} });
                                } else {
                                    os.put({ path: key, items: data, timestamp: Date.now() });
                                }
                            },
                            async saveVideos(videos) {
                                await this.set('all_videos', videos, 'tree_items');
                            },
                            async loadAllVideos() {
                                const res = await this.get('all_videos', 'tree_items');
                                return res ? res.items : null;
                            }
                        },

                        init() {
                            this.cache.init();
                            
                            // intercept clicks
                            document.body.addEventListener('click', e => {
                                const link = e.target.closest('a');
                                if (link && link.href.startsWith(window.location.origin) && link.getAttribute('href').startsWith('/?')) {
                                    e.preventDefault();
                                    this.navigate(link.getAttribute('href'));
                                }
                            });

                            // browser navigation
                            window.addEventListener('popstate', () => this.handleRoute());
                            
                            // initial load
                            this.handleRoute(true);
                            
                             // polling
                            this.startPolling();
                            
                            // Initialize Overlay
                            this.initOverlay();
                        },
                        
                        initOverlay() {
                            const overlay = document.getElementById('nav-loading-overlay');
                            if (!overlay) return;
                            
                            const hide = () => {
                                overlay.classList.add('opacity-0', 'pointer-events-none');
                                overlay.classList.remove('opacity-100');
                            };
                            
                            // Initial hide
                            if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', hide);
                            else hide();
                            
                            // Expose hide function to App
                            this.hideOverlay = hide;
                            this.showOverlay = () => {
                                overlay.classList.remove('opacity-0', 'pointer-events-none');
                                overlay.classList.add('opacity-100');
                            };
                        },

                        navigate(url) {
                            if (this.showOverlay) this.showOverlay();
                            // Small delay to allow UI to update
                            setTimeout(() => {
                                window.history.pushState({}, '', url);
                                this.handleRoute();
                            }, 50);
                        },

                        async handleRoute(isInitial = false) {
                            const params = new URLSearchParams(window.location.search);
                            const newMode = params.get('mode') || 'flat';
                            const newPath = params.get('path') || '';

                            // Reset State if needed
                            if (newMode !== this.state.mode || newPath !== this.state.path || !isInitial) {
                                this.state.mode = newMode;
                                this.state.path = newPath;
                                this.state.page = 1;
                                this.state.items = [];
                                this.state.hasMore = true;
                                this.state.isLoading = false;
                                
                                // Update UI Shell
                                this.updateHeader(newMode, newPath);
                                this.updateBottomNav(newMode);
                                
                                // Hide Overlay if cached content is ready significantly fast, done in renderItems
                                // But if we rely on network, it stays up.
                                
                                // Actually, for SPA, we should probably hide the Full-Screen overlay immediately
                                // and rely on the content loading spinner for the section.
                                // The "Nav Overlay" is for the transition feeling.
                                
                                if (this.hideOverlay) setTimeout(this.hideOverlay, 300);
                                
                                // Clear Grids & Reset Sentinel
                                document.getElementById('video-grid').innerHTML = ''; 
                                document.getElementById('tree-grid').innerHTML = '';
                                
                                // Show appropriate section
                                if (newMode === 'tree') {
                                    document.getElementById('video-section').classList.add('hidden');
                                    document.getElementById('tree-section').classList.remove('hidden');
                                    
                                    // Try Cache First
                                    const cached = await this.cache.get(newPath, 'tree_items');
                                    if (cached && cached.items && cached.items.length > 0) {
                                        this.renderItems(cached.items, 'tree');
                                    }
                                } else {
                                    document.getElementById('tree-section').classList.add('hidden');
                                    document.getElementById('video-section').classList.remove('hidden');
                                    
                                    // Try Cache First
                                    const cached = await this.cache.get(null, 'items');
                                    if (cached && cached.length > 0) {
                                        this.renderItems(cached, 'flat');
                                    }
                                }
                            }
                            
                            // Fetch Fresh Data
                            if (this.state.hasMore) this.loadPage();
                        },

                        /**
                         * Generates folder items from the flat cached list.
                         * Filters allVideos for items starting with current path.
                         */
                        generateTree(path) {
                            if (!this.state.allVideos || this.state.allVideos.length === 0) return [];
                            
                            const items = [];
                            const folders = new Set();
                            
                            // Normalize path (remove leading/trailing slashes)
                            const targetPath = path ? path.replace(/^\/|\/$/g, '') : '';
                            const depth = targetPath ? targetPath.split('/').length : 0;
                            
                            this.state.allVideos.forEach(video => {
                                // video.path is e.g. "Movies/Action/DieHard.mp4"
                                const relativePath = video.path;
                                
                                // Optimization: Skip if not relevant
                                if (targetPath && !relativePath.startsWith(targetPath + '/')) return;
                                
                                const parts = relativePath.split('/');
                                
                                // Check if we are at the right depth
                                // parts for file "A/B.mp4" (depth 0) is ["A", "B.mp4"] -> part[0] is A (folder)
                                // If targetPath="", depth=0. We look at parts[0].
                                
                                // Safety check for malformed paths
                                if (parts.length <= depth) return;
                                
                                const segment = parts[depth];
                                
                                if (parts.length === depth + 1) {
                                    // It is a file in the current directory
                                    items.push({
                                        name: video.name,
                                        type: 'file',
                                        size: video.size,
                                        path: video.path,
                                        lastModified: video.lastModified
                                    });
                                } else {
                                    // It is a folder
                                    if (!folders.has(segment)) {
                                        folders.add(segment);
                                        items.push({
                                            name: segment,
                                            type: 'dir',
                                            size: 0
                                        });
                                    }
                                }
                            });
                            
                            // Sort: Folders first, then Files
                            return items.sort((a, b) => {
                                if (a.type === b.type) return a.name.localeCompare(b.name, undefined, {numeric: true});
                                return a.type === 'dir' ? -1 : 1;
                            });
                        },

                        async loadPage() {
                            if (this.state.isLoading) return;
                            this.state.isLoading = true;
                            document.getElementById('loading-indicator').classList.remove('hidden');

                            try {
                                // 1. Ensure we have the full video list (Memory or IDB or Network)
                                if (this.state.allVideos.length === 0) {
                                    // Try IDB first
                                    const cachedList = await this.cache.loadAllVideos();
                                    if (cachedList && cachedList.length > 0) {
                                        this.state.allVideos = cachedList;
                                    } else {
                                        // Fetch from API
                                        // Try /api/cache first (Fastest)
                                        try {
                                            const res = await fetch('/api/cache');
                                            if (res.ok) {
                                                const data = await res.json();
                                                if (data.videos) {
                                                    this.state.allVideos = data.videos;
                                                    this.cache.saveVideos(data.videos); // Async save
                                                    
                                                    // Also update scanning status if available in data? 
                                                    // No, CacheData format usually just has videos.
                                                }
                                            }
                                        } catch (e) { console.warn('Cache API failed'); }
                                        
                                        // If still empty, fall back to /api/videos (Progressive)
                                        if (this.state.allVideos.length === 0) {
                                             const res = await fetch('/api/videos?page=1'); // Just get first page to start
                                             const data = await res.json();
                                             // Note: /api/videos only returns paginated list.
                                             // So we can't fully support Tree View until full scan completes/cache loads.
                                             // But we can support Flat View.
                                        }
                                    }
                                }

                                // 2. Render based on Mode using Local Data
                                if (this.state.mode === 'tree') {
                                    // PURE CLIENT-SIDE TREE
                                    const items = this.generateTree(this.state.path);
                                    this.renderItems(items, 'tree', true);
                                    
                                    // Update Header Breadcrumbs
                                    this.updateBreadcrumbs(this.state.path);
                                    
                                    // Update Count
                                    const countEl = document.getElementById('video-count');
                                    // This element is inside #video-section usually, tree has no count display by default in UI?
                                    // Wait, tree-section has no count header in HTML.
                                    
                                    this.state.isLoading = false;
                                    document.getElementById('loading-indicator').classList.add('hidden');
                                    
                                } else {
                                    // FLAT LIST (Client-Filtered or Server-Paginated?)
                                    // Optimized: Use Client-Side list if we have it!
                                    if (this.state.allVideos.length > 0) {
                                        const offset = (this.state.page - 1) * 20;
                                        const limit = 20;
                                        
                                        // Filter hidden? (Visibilty manager is server-side usually)
                                        // For now assume client has all valid videos.
                                        
                                        const slice = this.state.allVideos.slice(offset, offset + limit);
                                        this.renderItems(slice, 'flat', true);
                                        
                                        this.updateVideoCount(this.state.allVideos.length, false);
                                        
                                        if (offset + limit >= this.state.allVideos.length) {
                                            this.state.hasMore = false;
                                        } else {
                                            this.state.hasMore = true;
                                            this.state.page++;
                                        }
                                        
                                        // Update Recently Added (Client Logic)
                                        if (this.state.page === 2) { // Logic: only render recents on first load (page incremented to 2)
                                             this.renderRecentlyAdded();
                                        }
                                        
                                        this.state.isLoading = false;
                                        document.getElementById('loading-indicator').classList.add('hidden');
                                        
                                    } else {
                                        // Legacy / Fallback Server Pagination
                                        const url = `/api/videos?page=${'$'}{this.state.page}`;
                                        const res = await fetch(url);
                                        const data = await res.json();
                                        
                                        this.updateVideoCount(data.totalVideos, data.scanning);
                                        this.renderItems(data.videos, 'flat', true);
                                        
                                        this.state.hasMore = data.hasMore;
                                        if (data.hasMore) this.state.page++;
                                        
                                        if (data.scanning) setTimeout(() => this.loadPage(), 1000);
                                        
                                        this.state.isLoading = false;
                                        document.getElementById('loading-indicator').classList.add('hidden');
                                    }
                                }
                                
                                // 3. Background Poll for Updates (Scanning)
                                this.startPolling();
                                
                            } catch (e) {
                                console.error('Load Error:', e);
                                this.state.isLoading = false;
                                document.getElementById('loading-indicator').classList.add('hidden');
                            }
                        },

                        renderItems(items, mode, animate = false) {
                            const container = mode === 'tree' ? document.getElementById('tree-grid') : document.getElementById('video-grid');
                            if (!container) return;

                            // Clear skeletons on first successful render of content
                            const skeletons = container.querySelectorAll('.skeleton-card');
                            if (skeletons.length > 0) {
                                skeletons.forEach(s => s.remove());
                            }

                            const fragment = document.createDocumentFragment();
                            
                            items.forEach(item => {
                                const name = item.name;
                                // Dedup check
                                if (container.querySelector(`[data-name="${'$'}{name.replace(/"/g, '\\"')}"]`)) return;

                                const el = document.createElement('div');
                                if (mode === 'tree' && item.type === 'dir') {
                                     // Folder
                                     const newPath = this.state.path ? `${'$'}{this.state.path}/${'$'}{name}` : name;
                                     const href = `/?mode=tree&path=${'$'}{encodeURIComponent(newPath)}`;
                                     el.innerHTML = `<a href="${'$'}{href}" data-name="${'$'}{name}" class="group block bg-surface-light rounded-2xl p-4 border border-white/5 active:scale-95 transition-transform hover:border-primary/50">
                                        <div class="flex items-center gap-3 mb-2">
                                            <div class="w-10 h-10 rounded-full bg-primary/10 flex items-center justify-center text-primary">
                                                <span class="material-symbols-rounded">folder</span>
                                            </div>
                                            <h3 class="font-bold text-sm text-text-main line-clamp-1 break-all">${'$'}{name}</h3>
                                        </div>
                                        <div class="flex justify-between items-center text-xs text-text-sub"><span>Folder</span></div>
                                    </a>`;
                                } else {
                                     // Video
                                     const path = this.state.path ? `${'$'}{this.state.path}/${'$'}{name}` : name;
                                     // Use API thumb route
                                     const thumbUrl = `/api/thumbnail/${'$'}{encodeURIComponent(name)}?path=${'$'}{encodeURIComponent(path)}`;
                                     
                                     el.innerHTML = `<a href="/${'$'}{name}" data-name="${'$'}{name}" class="group block bg-surface-light rounded-2xl p-2 border border-white/5 active:scale-95 transition-transform ${'$'}{animate ? 'animate-in fade-in zoom-in duration-300' : ''}">
                                          <div class="relative aspect-[4/3] rounded-xl overflow-hidden bg-background mb-3">
                                              <img data-src="${'$'}{thumbUrl}" src="data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAxIDEiPjwvc3ZnPg==" loading="lazy" class="absolute inset-0 w-full h-full object-cover opacity-0 transition-opacity duration-500" />
                                              <div class="absolute inset-0 flex items-center justify-center -z-10 bg-surface">
                                                  <span class="material-symbols-rounded text-3xl text-text-sub/20 group-hover:text-primary transition-colors">movie</span>
                                              </div>
                                              <div class="absolute bottom-2 right-2 w-8 h-8 rounded-full bg-black/60 backdrop-blur flex items-center justify-center">
                                                  <span class="material-symbols-rounded text-white text-lg">play_arrow</span>
                                              </div>
                                          </div>
                                          <div class="px-1 pb-1">
                                              <h3 class="font-bold text-sm text-text-main line-clamp-2 leading-snug mb-1">${'$'}{name}</h3>
                                              <p class="text-[11px] text-text-sub">${'$'}{Math.round(item.size)} MB</p>
                                          </div>
                                     </a>`;
                                }
                                if (el.firstElementChild) fragment.appendChild(el.firstElementChild);
                            });
                            
                            container.appendChild(fragment);
                            this.observeImages();
                        },

                        observeImages() {
                            if (!this.io) {
                                this.io = new IntersectionObserver((entries, observer) => {
                                    entries.forEach(entry => {
                                        if (entry.isIntersecting) {
                                            const img = entry.target;
                                            img.src = img.dataset.src;
                                            img.classList.remove('opacity-0');
                                            observer.unobserve(img);
                                        }
                                    });
                                }, { rootMargin: '100px' });
                            }
                            document.querySelectorAll('img[loading="lazy"]').forEach(img => this.io.observe(img));
                        },

                        updateHeader(mode, path) {
                           // Logic to update Back Button / Title
                           // Note: Currently Header is server-rendered. We should make it dynamic or re-render it.
                           // For now, simple title update.
                           // Ideally, we replace the `header` content via JS.
                        },
                        
                        updateBottomNav(mode) {
                             document.getElementById('nav-tree').classList.toggle('bg-primary', mode === 'tree');
                             document.getElementById('nav-tree').classList.toggle('text-black', mode === 'tree');
                             document.getElementById('nav-tree').classList.toggle('bg-surface-light', mode !== 'tree');
                             document.getElementById('nav-home').classList.toggle('text-primary', mode !== 'tree');
                        },

                        updateVideoCount(total, scanning) {
                            const countEl = document.getElementById('video-count');
                            if (!countEl) return;
                            let text = (total || 0).toString();
                            text += this.state.mode === 'tree' ? ' Items' : ' Videos';
                            if (scanning) text += " (Scanning...)";
                            countEl.textContent = text;
                        },

                        updateBreadcrumbs(path) {
                            const container = document.getElementById('breadcrumbs');
                            if (!container) return;
                            
                            container.innerHTML = '';
                            
                            // Always add Home
                            const homeLink = document.createElement('a');
                            homeLink.href = '/?mode=tree';
                            homeLink.className = (path) ? "text-text-sub hover:text-text-main text-sm" : "text-primary font-bold text-sm";
                            homeLink.textContent = "Home";
                            container.appendChild(homeLink);
                            
                            if (!path) return;
                            
                            // Separator and Parts
                            const parts = path.split('/').filter(p => p);
                            let currentPath = "";
                            
                            parts.forEach((part, index) => {
                                // Add Separator
                                const sep = document.createElement('span');
                                sep.className = "text-text-sub";
                                sep.textContent = "/";
                                container.appendChild(sep);
                                
                                currentPath += (currentPath ? "/" : "") + part;
                                
                                const link = document.createElement('a');
                                const isLast = index === parts.length - 1;
                                link.href = `/?mode=tree&path=${'$'}{encodeURIComponent(currentPath)}`;
                                link.className = isLast ? "text-primary font-bold text-sm" : "text-text-sub hover:text-text-main text-sm";
                                link.textContent = part;
                                container.appendChild(link);
                            });
                        },
                        
                        renderRecentlyAdded() {
                            const container = document.getElementById('recently-added-grid');
                            if (!container) return;
                            
                            // Get top 4 recent
                            if (!this.state.allVideos || this.state.allVideos.length === 0) return;
                            
                            const recents = [...this.state.allVideos]
                                .sort((a, b) => b.lastModified - a.lastModified)
                                .slice(0, 4);
                                
                            if (recents.length === 0) {
                                container.innerHTML = '<div class="w-full text-center py-8 text-text-sub text-sm">No recent videos</div>';
                                return;
                            }
                            
                            container.innerHTML = '';
                            
                            recents.forEach(video => {
                                const name = video.name;
                                const size = Math.round(video.size || 0);
                                const el = document.createElement('a');
                                el.href = `/${'$'}{name}`;
                                el.className = "flex-none w-64 snap-start group relative rounded-2xl overflow-hidden aspect-video bg-surface-light border border-white/5";
                                el.innerHTML = `
                                    <img data-src="/api/thumbnail/${'$'}{encodeURIComponent(name)}" src="data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAxIDEiPjwvc3ZnPg==" loading="lazy" class="absolute inset-0 w-full h-full object-cover opacity-0 transition-opacity duration-500"/>
                                    <div class="absolute inset-0 bg-black/20 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity z-10">
                                        <div class="w-12 h-12 bg-white/20 backdrop-blur-sm rounded-full flex items-center justify-center"><span class="material-symbols-rounded text-white text-3xl">play_arrow</span></div>
                                    </div>
                                    <div class="absolute inset-0 flex items-center justify-center bg-white/5 -z-10"><span class="material-symbols-rounded text-4xl text-text-sub/20">movie</span></div>
                                    <div class="absolute inset-0 bg-gradient-to-t from-black/90 via-black/20 to-transparent"></div>
                                    <div class="absolute bottom-3 left-3 right-3">
                                        <h3 class="font-bold text-sm text-white line-clamp-1 mb-1">${'$'}{name}</h3>
                                        <div class="flex items-center gap-2 text-[10px] text-gray-300 font-medium">
                                            <span class="bg-primary/20 text-primary px-1.5 py-0.5 rounded">NEW</span>
                                            <span>${'$'}{size} MB</span>
                                        </div>
                                    </div>
                                `;
                                container.appendChild(el);
                            });
                            
                            // Re-observe images for recents
                            this.observeImages();
                        },

                        startPolling() {
                            // OPTIMIZATION: Smart polling - faster during scanning, slower when idle
                            let pollInterval = 2000; // Start with 2s
                            
                            const poll = async () => {
                                if (document.hidden || !navigator.onLine) return;
                                try {
                                    const res = await fetch('/api/status');
                                    const status = await res.json();
                                    const bar = document.getElementById('scan-progress-container');
                                    
                                    if (status.scanning) {
                                        pollInterval = 500; // Fast polling during scan
                                        bar.classList.remove('opacity-0', '-translate-y-4');
                                        document.getElementById('scan-count').textContent = status.count;
                                        
                                        // Live Refresh for Flat Mode - load new videos as they're discovered
                                        if (this.state.mode === 'flat' && !this.state.isLoading) {
                                            this.loadPage(); // Progressive load next batch
                                        }
                                    } else {
                                        pollInterval = 2000; // Slow polling when idle
                                        bar.classList.add('opacity-0', '-translate-y-4');
                                    }
                                } catch(e){}
                                
                                // Schedule next poll with current interval
                                setTimeout(poll, pollInterval);
                            };
                            
                            // Start polling
                            poll();
                        }
                    };

                    // Intersection Sentinel for Infinite Scroll
                    const sentinelCallback = (entries) => {
                        if (entries[0].isIntersecting && App.state.hasMore) {
                            App.loadPage();
                        }
                    };
                    const sentinelObserver = new IntersectionObserver(sentinelCallback, { rootMargin: '200px' });
                    
                    document.addEventListener('DOMContentLoaded', () => {
                        App.init();
                        const s = document.getElementById('loading-sentinel');
                        if (s) sentinelObserver.observe(s);
                    });
                </script>""")
        }

        // --- FOOTER ---
        write("""
    </main>
    
    <!-- Bottom Navigation -->
    <nav class="fixed bottom-0 left-0 right-0 bg-background/95 backdrop-blur-xl border-t border-white/5 px-6 py-2 pb-5 z-50">
        <div class="flex items-center justify-between max-w-sm mx-auto">
            <a href="/?theme=$themeParam" id="nav-home" class="flex flex-col items-center gap-1 ${if (mode != "tree") "text-primary" else "text-text-sub"}">
                <span class="material-symbols-rounded text-2xl">home</span>
                <span class="text-[10px] font-bold">Home</span>
            </a>
            
            <a href="/?mode=tree&theme=$themeParam" id="nav-tree" class="w-14 h-14 -mt-8 rounded-full flex items-center justify-center shadow-[0_0_20px_${if (mode == "tree") "rgba(250,198,56,0.3)" else "rgba(0,0,0,0)"}] border-4 border-background ${if (mode == "tree") "bg-primary text-black" else "bg-surface-light text-text-sub"} active:scale-90 transition-all">
                <span class="material-symbols-rounded text-3xl">folder</span>
            </a>
            
            <a href="/settings" class="flex flex-col items-center gap-1 text-text-sub hover:text-text-main transition-colors">
                <span class="material-symbols-rounded text-2xl">settings</span>
                <span class="text-[10px] font-medium">Settings</span>
            </a>
        </div>
    </nav>
    
    <script>
       // Legacy script removed - logic moved to App.initOverlay()
    </script>
</body>
</html>""")
        flush()
    }
}
