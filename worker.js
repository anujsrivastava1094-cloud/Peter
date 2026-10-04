const AI_MODEL = "@cf/google/gemma-4-26b-a4b-it";

function headers() {
  return {
    "content-type": "application/json; charset=UTF-8",
    "cache-control": "no-store",
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET,POST,OPTIONS",
    "access-control-allow-headers": "Content-Type"
  };
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: headers() });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: headers() });
    }

    if (url.pathname === "/api/health") {
      return json({
        ok: true,
        service: "PETER",
        worker: "online",
        aiBinding: Boolean(env.AI),
        memoryBinding: Boolean(env.PETER_MEMORY),
        databaseBinding: Boolean(env.PETER_DB),
        queueBinding: Boolean(env.PETER_EVENTS)
      });
    }

    if (url.pathname === "/api/memory") {
      if (!env.PETER_MEMORY) return json({ ok: false, error: "Memory binding is not available" }, 503);

      if (request.method === "GET") {
        const key = url.searchParams.get("key") || "profile:default";
        const value = await env.PETER_MEMORY.get(key);
        return json({ ok: true, key, value: value || "" });
      }

      if (request.method === "POST") {
        let body;
        try { body = await request.json(); }
        catch { return json({ ok: false, error: "Invalid JSON body" }, 400); }

        const key = typeof body?.key === "string" ? body.key.trim().slice(0, 200) : "";
        const value = typeof body?.value === "string" ? body.value.trim().slice(0, 10000) : "";
        if (!key || !value) return json({ ok: false, error: "Key and value are required" }, 400);

        await env.PETER_MEMORY.put(key, value);
        return json({ ok: true, key });
      }

      return json({ ok: false, error: "Method not allowed" }, 405);
    }

    if (url.pathname === "/api/plan") {
      if (request.method !== "POST") return json({ ok: false, error: "Method not allowed" }, 405);
      if (!env.AI) return json({ ok: false, error: "Workers AI binding is not available" }, 503);

      let body;
      try { body = await request.json(); }
      catch { return json({ ok: false, error: "Invalid JSON body" }, 400); }

      const requestText = typeof body?.request === "string" ? body.request.trim().slice(0, 6000) : "";
      if (!requestText) return json({ ok: false, error: "Plan request is required" }, 400);

      try {
        const result = await env.AI.run(AI_MODEL, {
          messages: [
            {
              role: "system",
              content: "You are PETER Plan Maker. Build realistic, actionable plans from natural-language goals. Understand English, Hindi and Hinglish. Reply in English. Identify the goal, constraints, available time, deadline, resources and priority when provided. If important information is missing, make reasonable assumptions and label them. Challenge impossible workloads instead of blindly agreeing. Return a compact plan with: Goal, Assumptions, Strategy, Milestones, Weekly Structure, Daily Actions, Time Budget, Risks, Recovery Rule, and First Action. Never claim that a task was scheduled or completed."
            },
            { role: "user", content: requestText }
          ],
          chat_template_kwargs: { enable_thinking: false }
        });

        const response = typeof result?.choices?.[0]?.message?.content === "string"
          ? result.choices[0].message.content.trim()
          : typeof result?.response === "string"
            ? result.response.trim()
            : "";

        if (!response) return json({ ok: false, error: "Planner returned no response" }, 502);

        if (env.PETER_DB) {
          try {
            await env.PETER_DB.prepare(
              "INSERT INTO events (event_type, payload) VALUES (?, ?)"
            ).bind(
              "plan_request",
              JSON.stringify({ request: requestText, response: response.slice(0, 8000) })
            ).run();
          } catch {}
        }

        return json({ ok: true, plan: response, model: AI_MODEL });
      } catch (error) {
        return json({
          ok: false,
          error: "Planner inference failed",
          detail: error instanceof Error ? error.message : String(error)
        }, 502);
      }
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
      const history = Array.isArray(body?.history)
        ? body.history.slice(-8).filter(item =>
            item &&
            (item.role === "user" || item.role === "assistant") &&
            typeof item.content === "string"
          )
        : [];
      const sessionId = typeof body?.sessionId === "string" && body.sessionId.trim()
        ? body.sessionId.trim().slice(0, 120)
        : "default";
      let savedMemory = "";
      let profileMemory = "";
      if (env.PETER_MEMORY) {
        try {
          savedMemory = (await env.PETER_MEMORY.get("conversation:" + sessionId)) || "";
          profileMemory = (await env.PETER_MEMORY.get("profile:" + sessionId)) || "";
          if (!profileMemory) profileMemory = (await env.PETER_MEMORY.get("profile:default")) || "";
        } catch {}
      }
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
              content: "You are PETER, a personal AI assistant and personal operating system. Understand English, Hindi, and Hinglish, including imperfect word order. Reply in English unless the user explicitly asks otherwise. Be practical, concise, natural, and honest. Never claim an action happened unless the application actually performed it." +
                (profileMemory ? "\nRelevant saved personal memory:\n" + profileMemory.slice(0, 5000) : "") + (savedMemory ? "\nRecent conversation context:\n" + savedMemory.slice(0, 5000) : "")
            },
            ...history.map(item => ({
              role: item.role,
              content: item.content.slice(0, 2500)
            })),
            {
              role: "user",
              content: message
            }
          ],
          chat_template_kwargs: {
            enable_thinking: false
          }
        });

        const response = typeof result?.choices?.[0]?.message?.content === "string"
          ? result.choices[0].message.content.trim()
          : typeof result?.response === "string"
            ? result.response.trim()
            : typeof result?.result === "string"
              ? result.result.trim()
              : "";

        if (env.PETER_MEMORY) {
          try {
            const nextMemory = [
              savedMemory,
              "User: " + message,
              "PETER: " + response
            ].filter(Boolean).join("\n").slice(-12000);
            await env.PETER_MEMORY.put("conversation:" + sessionId, nextMemory, {
              expirationTtl: 60 * 60 * 24 * 30
            });
          } catch {}
        }

        if (env.PETER_DB) {
          try {
            await env.PETER_DB.prepare(
              "INSERT INTO events (event_type, payload) VALUES (?, ?)"
            ).bind(
              "conversation",
              JSON.stringify({ sessionId, message, response: response.slice(0, 5000) })
            ).run();
          } catch {}
        }

        return json({
          ok: true,
          response,
          model: AI_MODEL,
          brain: true
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
