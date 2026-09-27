export default {
  fetch(request, env) {
    const url = new URL(request.url);
    if (url.hostname === "www.su46proj.site") {
      url.hostname = "su46proj.site";
      url.port = "";
      return Response.redirect(url.toString(), 301);
    }
    return env.ASSETS.fetch(request);
  },
};
