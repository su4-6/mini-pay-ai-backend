addEventListener("fetch", event => {
  const url = new URL(event.request.url);
  url.port = "8443";
  const newRequest = new Request(url.toString(), event.request);
  event.respondWith(fetch(newRequest));
});
