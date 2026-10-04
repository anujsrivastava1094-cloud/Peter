export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/api/health") {
      return new Response(JSON.stringify({
        ok: true,
        service: "PETER",
        worker: "online",
        aiBinding: Boolean(env.AI)
      }), {
        headers: { "content-type": "application/json; charset=UTF-8" }
      });
    }
    return env.ASSETS.fetch(request);
  }
};
