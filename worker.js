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

    if (url.pathname === "/api/actions") {
      if (!env.PETER_MEMORY) return json({ ok: false, error: "Action storage is not available" }, 503);

      let body;
      try { body = await request.json(); }
      catch { return json({ ok: false, error: "Invalid JSON body" }, 400); }

      const sessionId = typeof body?.sessionId === "string" && body.sessionId.trim()
        ? body.sessionId.trim().slice(0, 120)
        : "default";
      const action = typeof body?.action === "string" ? body.action.trim().toLowerCase() : "";

      const key = "state:" + sessionId;
      let state = { tasks: [], notes: [], focus: null, updatedAt: null };
      try {
        const stored = await env.PETER_MEMORY.get(key);
        if (stored) state = { ...state, ...JSON.parse(stored) };
      } catch {}

      const now = new Date().toISOString();

      if (action === "add_task") {
        const title = typeof body?.title === "string" ? body.title.trim().slice(0, 300) : "";
        if (!title) return json({ ok: false, error: "Task title is required" }, 400);
        const task = {
          id: crypto.randomUUID(),
          title,
          status: "pending",
          createdAt: now
        };
        state.tasks.unshift(task);
        state.tasks = state.tasks.slice(0, 100);
        state.updatedAt = now;
        await env.PETER_MEMORY.put(key, JSON.stringify(state));
        return json({ ok: true, action, task, state });
      }

      if (action === "complete_task") {
        const taskId = typeof body?.taskId === "string" ? body.taskId : "";
        const title = typeof body?.title === "string" ? body.title.trim().toLowerCase() : "";
        const task = state.tasks.find(t =>
          (taskId && t.id === taskId) ||
          (title && t.title.toLowerCase() === title)
        );
        if (!task) return json({ ok: false, error: "Task not found" }, 404);
        task.status = "completed";
        task.completedAt = now;
        state.updatedAt = now;
        await env.PETER_MEMORY.put(key, JSON.stringify(state));
        return json({ ok: true, action, task, state });
      }

      if (action === "add_note") {
        const content = typeof body?.content === "string" ? body.content.trim().slice(0, 2000) : "";
        if (!content) return json({ ok: false, error: "Note content is required" }, 400);
        const note = { id: crypto.randomUUID(), content, createdAt: now };
        state.notes.unshift(note);
        state.notes = state.notes.slice(0, 100);
        state.updatedAt = now;
        await env.PETER_MEMORY.put(key, JSON.stringify(state));
        return json({ ok: true, action, note, state });
      }

      if (action === "start_focus") {
        const minutes = Math.min(180, Math.max(1, Number(body?.minutes) || 25));
        state.focus = {
          status: "running",
          minutes,
          startedAt: now,
          endsAt: new Date(Date.now() + minutes * 60000).toISOString()
        };
        state.updatedAt = now;
        await env.PETER_MEMORY.put(key, JSON.stringify(state));
        return json({ ok: true, action, focus: state.focus, state });
      }

      if (action === "stop_focus") {
        state.focus = null;
        state.updatedAt = now;
        await env.PETER_MEMORY.put(key, JSON.stringify(state));
        return json({ ok: true, action, state });
      }

      if (action === "get_state") {
        return json({ ok: true, action, state });
      }

      return json({
        ok: false,
        error: "Unknown action",
        supportedActions: ["add_task", "complete_task", "add_note", "start_focus", "stop_focus", "get_state"]
      }, 400);
    }

    if (url.pathname === "/api/command") {
      if (request.method !== "POST") return json({ ok: false, error: "Method not allowed" }, 405);

      let body;
      try { body = await request.json(); }
      catch { return json({ ok: false, error: "Invalid JSON body" }, 400); }

      const raw = typeof body?.command === "string" ? body.command.trim().slice(0, 500) : "";
      if (!raw) return json({ ok: false, error: "Command is required" }, 400);

      const s = raw.toLowerCase().replace(/[.,!?;:()[\]{}]/g, " ").replace(/\s+/g, " ").trim();
      let intent = "chat";
      let action = null;
      let payload = {};

      /* Web-intelligence detection. No external search is performed here,
         keeping the current PETER deployment ₹0-first. */
      const webPatterns = [
        /\b(?:latest|current|today|today's|todays|now|recent|recently|this week|this month|news|live|real[- ]time|updated|update)\\b/i,
        /\b(?:weather|temperature|stock price|exchange rate|score|standings|schedule)\\b/i,
        /\b(?:who is|what happened|what are the latest|what is the current|how much is)\\b/i
      ];
      const likelyWeb = webPatterns.some(function(re){ return re.test(raw); });

      let m = s.match(/^(?:add|create|make) (?:a )?(?:task|todo|to do)(?: called| named| for| to)? (.+)$/);
      if (m) {
        intent = "action"; action = "add_task"; payload = { title: m[1].trim() };
      } else if ((m = s.match(/^(?:finish|complete|done|mark) (?:my )?(?:task|todo|to do)(?: called| named)? (.+)$/))) {
        intent = "action"; action = "complete_task"; payload = { title: m[1].trim() };
      } else if ((m = s.match(/^(?:remember|remember this|don't let me forget|dont let me forget)(?: that)? (.+)$/))) {
        intent = "action"; action = "add_note"; payload = { content: m[1].trim() };
      } else if ((m = s.match(/^(?:save|add|take|make) (?:a )?(?:note|memo|capture)(?::| that| about| saying| says)? (.+)$/))) {
        intent = "action"; action = "add_note"; payload = { content: m[1].trim() };
      } else if (/^(?:stop|cancel) focus(?: mode)?$/.test(s)) {
        intent = "action"; action = "stop_focus";
      } else if ((m = s.match(/^(?:start|begin) focus(?: mode)?(?: for)? (\d{1,3}) ?(?:m|min|mins|minute|minutes)?$/))) {
        intent = "action"; action = "start_focus"; payload = { minutes: Number(m[1]) };
      } else if (/^(?:show|open) (?:my )?(?:tasks|todos|to dos)$/.test(s)) {
        intent = "navigate"; action = "show_tasks";
      } else if (/^(?:show|open) (?:my )?(?:notes|memos)$/.test(s)) {
        intent = "navigate"; action = "show_notes";
      } else if (/^(?:show|open) (?:today's |todays )?(?:timeline|schedule)$/.test(s)) {
        intent = "navigate"; action = "show_timeline";
      } else if (/^(?:go )?(?:home|dashboard)$/.test(s)) {
        intent = "navigate"; action = "home";
      }

      return json({
        ok: true,
        intent,
        action,
        payload,
        original: raw,
        requiresAI: intent === "chat"
      });
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
