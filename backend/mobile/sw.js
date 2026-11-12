// Network-only app: do not retain private profiles, pairing secrets, or community API responses.
self.addEventListener('install',()=>self.skipWaiting());
self.addEventListener('activate',event=>event.waitUntil(self.clients.claim()));
