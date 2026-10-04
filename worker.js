const AI_MODEL = "@cf/google/gemma-4-26b-a4b-it";

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=UTF-8",
      "cache-control": "no-store"
    }
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/api/health") {
      return json({
        ok: true,
        service: "PETER",
        worker: "online",
        aiBinding: Boolean(env.AI)
      });
    }

    if (url.pathname === "/api/ai") {
      if (request.method !== "POST") {
        return json({ ok: false, error: "Method not allowed" }, 405);
      }

      if (!env.AI) {
        return json({ ok: false, error: "Workers AI binding is not available" }, 503);
      }

      let body;
      try {
        body = await request.json();
      } catch {
        return json({ ok: false, error: "Invalid JSON body" }, 400);
      }

      const message = typeof body?.message === "string" ? body.message.trim() : "";
      if (!message) {
        return json({ ok: false, error: "Message is required" }, 400);
      }

      if (message.length > 4000) {
        return json({ ok: false, error: "Message is too long" }, 413);
      }

      try {
        const result = await env.AI.run(AI_MODEL, {
          messages: [
            {
              role: "system",
              content: "You are PETER, a concise personal AI assistant. Be helpful, clear, practical, and natural. The user may speak Hindi, Hinglish, or English; understand all three and reply in English unless the user explicitly asks for another language. Do not claim to have performed actions you cannot perform."
            },
            {
              role: "user",
              content: message
            }
          ],
          chat_template_kwargs: {
            enable_thinking: false
          }
        });

        const response = typeof result?.response === "string"
          ? result.response
          : typeof result?.result === "string"
            ? result.result
            : result?.response?.text || result?.result?.response || JSON.stringify(result);

        return json({
          ok: true,
          response,
          model: AI_MODEL
        });
      } catch (error) {
        return json({
          ok: false,
          error: "AI inference failed",
          detail: error instanceof Error ? error.message : String(error)
        }, 502);
      }
    }

    return env.ASSETS.fetch(request);
  }
};
