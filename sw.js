const CACHE = 'peter-v8';

const APP_SHELL = [
  './',
  './index.html',
  './manifest.json',
  './icon.svg'
];

self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE)
      .then(cache => cache.addAll(APP_SHELL))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys =>
        Promise.all(
          keys
            .filter(key => key !== CACHE && key.startsWith('peter-'))
            .map(key => caches.delete(key))
        )
      )
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', event => {
  if (event.request.method !== 'GET') return;

  const request = event.request;

  // Always check the network first for HTML so new PETER builds
  // appear promptly after a deployment.
  if (
    request.mode === 'navigate' ||
    request.destination === 'document' ||
    request.url.endsWith('/index.html')
  ) {
    event.respondWith(
      fetch(request)
        .then(response => {
          const copy = response.clone();
          caches.open(CACHE).then(cache => cache.put(request, copy));
          return response;
        })
        .catch(() =>
          caches.match(request).then(
            cached => cached || caches.match('./index.html')
          )
        )
    );
    return;
  }

  // Static assets can use the cache, with a network fallback.
  event.respondWith(
    caches.match(request)
      .then(cached => cached || fetch(request))
  );
});
