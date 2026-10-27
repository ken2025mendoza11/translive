// Optional cloud proxy. API keys stay here, never in the Android app.
// Set TRANSLATOR_KEY (Microsoft) and optionally GEMINI_KEY. If neither is set, the app uses on-device ML Kit.

const perDevice = new Map<string, { count: number; reset: number }>();
const MONTHLY_CAP = 1_500_000;

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method" }, 405);
  const key = Deno.env.get("TRANSLATOR_KEY");
  if (!key) return json({ error: "translator_not_configured" }, 503);

  const body = await req.json().catch(() => null) as { texts?: string[]; deviceKey?: string } | null;
  const texts = (body?.texts ?? []).slice(0, 40).map((t) => String(t).slice(0, 200));
  const deviceKey = String(body?.deviceKey ?? "anon").slice(0, 80);
  if (texts.length === 0) return json({ translations: [] });

  const now = Date.now();
  const bucket = perDevice.get(deviceKey) ?? { count: 0, reset: now + 60_000 };
  if (now > bucket.reset) {
    bucket.count = 0;
    bucket.reset = now + 60_000;
  }
  bucket.count += texts.join("").length;
  perDevice.set(deviceKey, bucket);
  if (bucket.count > 8_000) return json({ error: "rate_limited" }, 429);
  if (bucket.count > MONTHLY_CAP) return json({ error: "cap" }, 429);

  const translations = texts.map((zh) => ({ zh, en: zh, layer: "proxy" }));
  const endpoint = Deno.env.get("TRANSLATOR_ENDPOINT") ?? "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&from=zh-Hans&to=en";
  const res = await fetch(endpoint, {
    method: "POST",
    headers: { "Ocp-Apim-Subscription-Key": key, "Content-Type": "application/json" },
    body: JSON.stringify(texts.map((text) => ({ text }))),
  });
  if (!res.ok) return json({ error: "upstream", translations }, 502);
  const data = await res.json();
  if (Array.isArray(data)) {
    data.forEach((row, i) => {
      const en = row?.translations?.[0]?.text;
      if (typeof en === "string" && translations[i]) translations[i] = { zh: texts[i], en, layer: "microsoft" };
    });
  }
  return json({ translations });
});

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}
